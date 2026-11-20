package vn.aimtt.capture;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.aimtt.common.ApiException;
import vn.aimtt.job.*;
import vn.aimtt.llm.*;
import vn.aimtt.meeting.MeetingService;
import vn.aimtt.task.*;
import vn.aimtt.transcript.ParsedTranscript;

@Service
public class CaptureService {
    public record Source(UUID segmentId, int sequence, String text, long startMs, long endMs) {
        Extraction.Source extraction() { return new Extraction.Source(segmentId, sequence, text); }
    }
    public record Session(UUID id, UUID owner, String mode, String status, String title, LocalDate date, String timezone,
                          String provider, String model, String prompt, String schema, String sttModel, long version,
                          int next, int processed, String sources, String events, String overrides, String error, UUID meetingId,
                          UUID lease, Instant expires) {}
    public record Lease(UUID id, UUID token) {}
    private final JdbcTemplate db; private final ObjectMapper json; private final AudioProperties audio;
    private final AnalysisPolicy policy; private final AnalysisProperties analysis; private final MeetingService meetings;
    private final AnalysisJobStore jobs;
    public CaptureService(JdbcTemplate db, ObjectMapper json, AudioProperties audio, AnalysisPolicy policy, AnalysisProperties analysis,
                          MeetingService meetings, AnalysisJobStore jobs) {
        this.db=db; this.json=json; this.audio=audio; this.policy=policy; this.analysis=analysis;
        this.meetings=meetings; this.jobs=jobs;
    }
    public String encode(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException(); } }
    <T> T decode(String value, TypeReference<T> type) { try { return json.readValue(value,type); } catch(Exception e) { throw new IllegalStateException(); } }
    private static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT,"CAPTURE_CONFLICT",message); }
    public Session get(UUID id) {
        return db.query("select * from capture_sessions where id=?", (r,n)->new Session(r.getObject("id",UUID.class),r.getObject("user_id",UUID.class),
                r.getString("mode"),r.getString("status"),r.getString("title"),r.getObject("meeting_date",LocalDate.class),r.getString("timezone"),
                r.getString("provider_id"),r.getString("model"),r.getString("prompt_version"),r.getString("schema_version"),r.getString("stt_model"),
                r.getLong("version"),r.getInt("next_sequence"),r.getInt("processed_sequence"),r.getString("sources"),r.getString("events"),r.getString("overrides"),
                r.getString("error_code"),r.getObject("meeting_id",UUID.class),r.getObject("lease_id",UUID.class),r.getTimestamp("expires_at").toInstant()),id)
                .stream().findFirst().orElseThrow(ApiException::notFound);
    }
    public List<Map<String,Object>> list(UUID owner) {
        return db.queryForList("select id,mode,status,title,created_at from capture_sessions where user_id=? and expires_at>now() and status<>'CANCELLED' order by created_at desc limit 20",owner);
    }
    public Session owned(UUID owner, UUID id) { var s=get(id); if(!s.owner().equals(owner)||s.expires().isBefore(Instant.now())) throw ApiException.notFound(); return s; }
    private Session lock(UUID owner, UUID id) {
        if(db.query("select id from capture_sessions where id=? and user_id=? for update",(r,n)->1,id,owner).isEmpty()) throw ApiException.notFound();
        return owned(owner,id);
    }
    public List<Source> sources(Session s) { return decode(s.sources(),new TypeReference<List<Source>>(){}); }
    public Map<String,Object> view(UUID owner, UUID id) {
        var s=owned(owner,id); var result=new LinkedHashMap<String,Object>();
        result.put("id",s.id()); result.put("mode",s.mode()); result.put("status",s.status()); result.put("version",s.version());
        result.put("nextSequence",s.next()); result.put("processedSequence",s.processed()); result.put("errorCode",s.error());
        // Chunks uploaded but not yet transcribed: lets the panel show "waiting for STT" when the server is busy.
        result.put("pendingChunks",Math.max(0,s.next()-s.processed()-1));
        result.put("meetingId",s.meetingId()); result.put("expiresAt",s.expires()); result.put("sources",sources(s));
        result.put("analysisProvider",s.provider());
        return result;
    }
    @Transactional
    public UUID create(UUID owner, JsonNode body, String key) {
        if(key==null || !key.matches("[A-Za-z0-9._:-]{8,128}")) throw ApiException.invalid("Cần Idempotency-Key cho phiên audio.");
        if(body==null || !body.isObject()) throw ApiException.invalid("Thiếu metadata phiên audio.");
        body.fieldNames().forEachRemaining(name->{ if(!Set.of("mode","title","meetingDate","timezone","providerId").contains(name)) throw ApiException.invalid("Metadata audio không hỗ trợ."); });
        if(!audio.configured()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"STT_NOT_CONFIGURED","Cấu hình dịch vụ STT ở backend rồi khởi động lại.");
        String mode=body.path("mode").asText(); if(!Set.of("FILE","LIVE").contains(mode)) throw ApiException.invalid("Chọn FILE hoặc LIVE.");
        String title=body.path("title").asText(""); if(title.length()>255) throw ApiException.invalid("Tiêu đề tối đa 255 ký tự.");
        LocalDate date=null; String zone=body.path("timezone").asText("");
        try { if(!body.path("meetingDate").asText("").isBlank()) date=LocalDate.parse(body.path("meetingDate").asText());
            if(!zone.isBlank() && !ZoneId.getAvailableZoneIds().contains(zone)) throw new IllegalArgumentException();
        } catch(Exception e) { throw ApiException.invalid("Ngày hoặc múi giờ không hợp lệ."); }
        var definition="LIVE".equals(mode)?policy.resolve(body.path("providerId").asText(),"llm-v1",analysis):null;
        db.query("select id from users where id=? for update",(r,n)->1,owner);
        var previous=db.query("select id from capture_sessions where user_id=? and create_key=?",(r,n)->r.getObject(1,UUID.class),owner,key);
        if(!previous.isEmpty()) {
            var old=get(previous.get(0));
            if(!old.mode().equals(mode)||!Objects.equals(old.title(),title)||!Objects.equals(old.date(),date)||!Objects.equals(old.timezone(),zone)
                    || !Objects.equals(old.provider(),definition==null?null:definition.providerId())) throw conflict("Key đã dùng cho metadata khác.");
            return old.id();
        }
        if(db.queryForObject("select count(*) from capture_sessions where user_id=? and expires_at>now() and status in ('OPEN','DRAINING','FAILED','READY')",Integer.class,owner)>=2)
            throw conflict("Tối đa hai phiên audio đang xử lý/chờ. Hủy hoặc lưu phiên cũ trước.");
        UUID id=UUID.randomUUID();
        db.update("""
                insert into capture_sessions(id,user_id,mode,status,title,meeting_date,timezone,provider_id,model,prompt_version,schema_version,stt_model,create_key)
                values (?,?,?,'OPEN',?,?,?,?,?,?,?,?,?)
                """,id,owner,mode,title,date,zone,definition==null?null:definition.providerId(),definition==null?null:definition.model(),
                definition==null?null:definition.promptVersion(),definition==null?null:definition.schemaVersion(),audio.captureModel(),key);
        return id;
    }
    @Transactional
    public void upload(UUID owner, UUID id, int sequence, long startMs, String name, byte[] bytes) {
        var s=lock(owner,id); String suffix=AudioInput.validate(name,bytes), hash=AudioInput.hash(bytes);
        var old=db.query("select content_hash, start_ms from capture_chunks where session_id=? and sequence=?",(r,n)->Map.entry(r.getString(1),r.getLong(2)),id,sequence);
        if(!old.isEmpty()) { if(!old.get(0).getKey().equals(hash)||old.get(0).getValue()!=startMs) throw conflict("Đoạn này đã có nội dung khác."); return; }
        if(!s.status().equals("OPEN")||s.expires().isBefore(Instant.now())) throw conflict("Phiên không còn nhận audio.");
        if(sequence!=s.next() || sequence<0 || sequence>=480 || startMs<0 || startMs>7200000) throw conflict("Sequence/mốc thời gian không hợp lệ hoặc đã quá giới hạn hai giờ.");
        if("FILE".equals(s.mode()) && (sequence!=0||startMs!=0)) throw conflict("File audio chỉ nhận một file.");
        if(sequence-s.processed()>8) throw conflict("Hàng đợi audio đang chậm. Tạm dừng thu và đợi xử lý.");
        if(sequence>0) {
            long last=db.queryForObject("select start_ms from capture_chunks where session_id=? and sequence=?",Long.class,id,sequence-1);
            if(startMs<=last) throw conflict("Mốc thời gian đoạn phải tăng.");
        }
        db.update("insert into capture_chunks(session_id,sequence,content_hash,suffix,audio,start_ms) values (?,?,?,?,?,?)",id,sequence,hash,suffix,bytes,startMs);
        db.update("update capture_sessions set next_sequence=next_sequence+1,version=version+1 where id=?",id);
    }
    @Transactional
    public void stop(UUID owner, UUID id, int count) {
        var s=lock(owner,id);
        if(Set.of("DRAINING","READY","PUBLISHED").contains(s.status())) return;
        if(!s.status().equals("OPEN")||count!=s.next()) throw conflict("Chưa upload đủ đoạn cuối; giữ file để thử gửi lại trước khi Stop.");
        db.update("update capture_sessions set status='DRAINING',version=version+1 where id=?",id);
    }
    @Transactional
    public void cancel(UUID owner, UUID id) {
        var s=lock(owner,id); if(s.status().equals("PUBLISHED")) throw conflict("Meeting đã lưu; dùng chức năng xóa meeting.");
        db.update("delete from capture_chunks where session_id=?",id);
        db.update("update capture_sessions set status='CANCELLED',sources='[]',events='[]',overrides='{}',lease_id=null,lease_until=null,version=version+1 where id=?",id);
    }
    @Transactional
    public void retry(UUID owner, UUID id) {
        var s=lock(owner,id); if(!s.status().equals("FAILED")) throw conflict("Chỉ retry phiên lỗi.");
        if(s.expires().isBefore(Instant.now())) throw conflict("Phiên đã hết hạn.");
        db.update("update capture_chunks set status=case when transcript is null then 'QUEUED' else 'TRANSCRIBED' end,error_code=null where session_id=? and sequence=?",id,s.processed()+1);
        db.update("update capture_sessions set status=?,error_code=null,lease_id=null,lease_until=null,version=version+1 where id=?",s.mode().equals("LIVE")?"OPEN":"DRAINING",id);
    }
    @Transactional
    public Optional<Lease> claim() {
        // Expired I/O is ambiguous: fail visibly; retry remains an explicit user action.
        db.update("update capture_sessions set status='FAILED',error_code='CAPTURE_WORKER_INTERRUPTED',lease_id=null,lease_until=null,version=version+1 where lease_until < now() and status in ('OPEN','DRAINING')");
        var ids=db.query("""
                select id from capture_sessions s where s.status in ('OPEN','DRAINING') and s.lease_id is null and expires_at>now()
                and (processed_sequence+1<next_sequence or status='DRAINING')
                order by coalesce(last_claimed_at,created_at), created_at for update skip locked limit 1
                """,(r,n)->r.getObject(1,UUID.class));
        if(ids.isEmpty()) return Optional.empty(); UUID token=UUID.randomUUID();
        // Round robin: a session goes to the back of the queue after each chunk, so one long meeting cannot starve others.
        db.update("update capture_sessions set lease_id=?,lease_until=now()+interval '90 seconds',last_claimed_at=clock_timestamp() where id=?",token,ids.get(0));
        return Optional.of(new Lease(ids.get(0),token));
    }
    public boolean heartbeat(Lease lease) {
        return db.update("update capture_sessions set lease_until=now()+interval '90 seconds' where id=? and lease_id=? and lease_until>now() and status in ('OPEN','DRAINING')",lease.id(),lease.token())==1;
    }
    public record Chunk(byte[] audio, String suffix, long startMs, String transcript) {}
    public Chunk chunk(Session s) {
        return db.queryForObject("select audio,suffix,start_ms,transcript::text from capture_chunks where session_id=? and sequence=?",
                (r,n)->new Chunk(r.getBytes(1),r.getString(2),r.getLong(3),r.getString(4)),s.id(),s.processed()+1);
    }
    @Transactional
    public void checkpointStt(Lease lease, List<SttProvider.Segment> transcript) {
        var s=lock(get(lease.id()).owner(),lease.id()); if(!heartbeat(lease)) throw new JobFailure("LEASE_LOST",false);
        db.update("update capture_chunks set transcript=?::jsonb,audio=null,status='TRANSCRIBED' where session_id=? and sequence=?",encode(transcript),s.id(),s.processed()+1);
    }
    @Transactional
    public void completeChunk(Lease lease, List<Source> sources) {
        var s=lock(get(lease.id()).owner(),lease.id()); if(!heartbeat(lease)) return;
        db.update("update capture_chunks set transcript=null,audio=null,status='COMPLETED' where session_id=? and sequence=?",s.id(),s.processed()+1);
        db.update("update capture_sessions set sources=?::jsonb,processed_sequence=processed_sequence+1,lease_id=null,lease_until=null,version=version+1 where id=?",encode(sources),s.id());
    }
    @Transactional
    public void drained(Lease lease) {
        var s=lock(get(lease.id()).owner(),lease.id()); if(!heartbeat(lease)) return;
        if(s.processed()+1!=s.next()||!s.status().equals("DRAINING")) return;
        db.update("update capture_sessions set status='READY',lease_id=null,lease_until=null,version=version+1 where id=?",s.id());
    }
    @Transactional
    public void fail(Lease lease, String code) {
        if(!heartbeat(lease)) return;
        db.update("update capture_sessions set status='FAILED',error_code=?,lease_id=null,lease_until=null,version=version+1 where id=? and lease_id=?",code,lease.id(),lease.token());
    }
    /**
     * Saves the transcript as a meeting. A FAILED live session with some transcript can still be saved, so a late STT
     * error never loses what was already recognised. For LIVE sessions the one analysis job is queued here (the
     * normal analysis worker runs it with retry/rate limiting), replacing the per-chunk LLM calls of earlier versions.
     */
    @Transactional
    public UUID publish(UUID owner, UUID id) {
        jobs.lockOwner(owner);
        var s=lock(owner,id); if(s.status().equals("PUBLISHED") && s.meetingId()!=null) return s.meetingId();
        var source=sources(s);
        if(!s.status().equals("READY") && !(s.status().equals("FAILED") && !source.isEmpty())) throw conflict("Chờ xử lý hết đoạn cuối rồi lưu meeting.");
        if(source.isEmpty()) throw ApiException.invalid("Không có lời nói được nhận dạng; hủy phiên và thử nguồn khác.");
        var raw=new StringBuilder(); var segments=new ArrayList<ParsedTranscript.Segment>();
        for(var item:source) {
            if(!raw.isEmpty()) raw.append('\n'); int start=raw.length(); raw.append(item.text());
            segments.add(new ParsedTranscript.Segment(item.sequence(),null,stamp(item.startMs()),item.text(),start,raw.length(),
                    Map.of("rawStart",start,"rawEnd",raw.length(),"sourceType","AUDIO","startMs",item.startMs(),"endMs",item.endMs(),"segmentSequence",item.sequence())));
        }
        var meeting=meetings.create(owner,s.title(),s.date(),s.timezone(),new ParsedTranscript("AUDIO",raw.toString(),raw.toString(),segments,List.of("STT_REVIEW_REQUIRED")));
        if("LIVE".equals(s.mode())) queueAnalysis(owner,meeting.meetingId(),s);
        db.update("delete from capture_chunks where session_id=?",id);
        db.update("update capture_sessions set status='PUBLISHED',meeting_id=?,sources='[]',events='[]',overrides='{}',version=version+1 where id=?",meeting.meetingId(),id);
        return meeting.meetingId();
    }
    /** Best effort: without a ready provider or with the user's job quota used up, the meeting is saved and analysed manually. */
    private void queueAnalysis(UUID owner, UUID meetingId, Session s) {
        if(s.provider()==null || policy.views().stream().noneMatch(v->v.providerId().equals(s.provider()) && v.providerReady())) return;
        if(jobs.activeForOwner(owner)>=analysis.maxActivePerUser()) return;
        jobs.create(jobs.lockMeeting(owner,meetingId),"capture:"+s.id(),policy.resolve(s.provider(),AnalysisPolicy.LLM_ID,analysis));
    }
    public static String stamp(long ms) { return "%02d:%02d:%02d.%03d".formatted(ms/3600000,(ms/60000)%60,(ms/1000)%60,ms%1000); }
    public void expire() { db.update("delete from capture_sessions where expires_at<now()"); }
}
