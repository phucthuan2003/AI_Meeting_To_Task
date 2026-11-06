package vn.aimtt.capture;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.*;
import vn.aimtt.job.JobFailure;
import vn.aimtt.llm.*;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="app.audio.worker-enabled",havingValue="true")
public class CaptureWorker {
    private final CaptureService store; private final SttProvider stt; private final List<LlmProvider> providers;
    private final ExtractionValidator validator; private final ObjectMapper json;
    public CaptureWorker(CaptureService store, SttProvider stt, List<LlmProvider> providers, ExtractionValidator validator, ObjectMapper json) {
        this.store=store;this.stt=stt;this.providers=providers;this.validator=validator;this.json=json;
    }
    @Scheduled(fixedDelay=1000)
    public void poll() { store.claim().ifPresent(this::process); }
    @Scheduled(fixedDelay=600000)
    public void expire() { store.expire(); }
    public void process(CaptureService.Lease lease) {
        try {
            var s=store.get(lease.id());
            if(s.processed()+1==s.next()) { store.drained(lease); return; }
            var chunk=store.chunk(s);
            List<SttProvider.Segment> transcript;
            if(chunk.transcript()!=null) transcript=json.readValue(chunk.transcript(),new TypeReference<List<SttProvider.Segment>>(){});
            else {
                transcript=stt.transcribe(chunk.audio(),chunk.suffix(),s.sttModel(),()->store.heartbeat(lease));
                store.checkpointStt(lease,transcript);
            }
            var sources=new ArrayList<>(store.sources(s)); var fresh=new ArrayList<Extraction.Source>();
            for(var row:transcript) {
                long from=chunk.startMs()+row.startMs(),to=chunk.startMs()+row.endMs();
                var source=new CaptureService.Source(UUID.randomUUID(),sources.size(),row.text().replaceAll("[\\r\\n]+"," "),from,to);
                sources.add(source); fresh.add(source.extraction());
            }
            if(sources.stream().mapToInt(v->v.text().length()+1).sum()>200000) throw new JobFailure("TRANSCRIPT_OVER_BUDGET",false);
            var events=new ArrayList<>(store.events(s));
            if("LIVE".equals(s.mode())&&!fresh.isEmpty()) {
                if(!ExtractionPrompt.versionFor(s.provider()).equals(s.prompt())||!ExtractionPrompt.SCHEMA_VERSION.equals(s.schema())) throw new JobFailure("POLICY_VERSION_UNAVAILABLE",false);
                var provider=providers.stream().filter(p->p.id().equals(s.provider())).findFirst().orElseThrow(()->new JobFailure("PROVIDER_NOT_CONFIGURED",false));
                var state=validator.reconcile(store.snapshot(s),events,null,null,0);
                var data=json.createObjectNode(); data.put("title",s.title()); data.put("meeting_date",s.date()==null?null:s.date().toString());data.put("timezone",s.timezone());
                data.set("segments",json.valueToTree(fresh));data.set("known_tasks",json.valueToTree(state.candidates().stream().map(c->{
                    var task=new LinkedHashMap<String,Object>();task.put("task_ref",c.taskRef());task.put("task_name",c.taskName());
                    task.put("assignee_raw",c.assigneeRaw());task.put("deadline_raw",c.deadlineRaw());return task;
                }).toList()));
                data.set("part",json.valueToTree(Map.of("index",s.processed()+1,"overlap_until_sequence",-1)));
                JsonNode schema;try(var in=new ClassPathResource("llm/meeting-events-v1.schema.json").getInputStream()){schema=json.readTree(in);}
                if(data.toString().getBytes(StandardCharsets.UTF_8).length>40000) throw new JobFailure("TRANSCRIPT_OVER_BUDGET",false);
                var output=provider.extract(store.snapshot(s),data.toString(),schema,()->store.heartbeat(lease),ExtractionPrompt.chunked());
                store.recordUsage(lease,output);
                Set<String> known=new HashSet<>(); for(var item:events) if(item.event().event_type().equals("CREATE")) known.add(item.event().task_ref());
                events.addAll(validator.parse(fresh,output,known));
                if(events.size()>100) throw new JobFailure("LIVE_EVENT_LIMIT",false);
                validator.reconcile(store.snapshot(s),events,null,null,0);
            }
            if(store.encode(sources).getBytes(StandardCharsets.UTF_8).length>1048576 || store.encode(events).getBytes(StandardCharsets.UTF_8).length>1048576)
                throw new JobFailure("RESULT_OVER_BUDGET",false);
            store.completeChunk(lease,sources,events);
        } catch(JobFailure e) { store.fail(lease,e.code()); }
        catch(Exception e) { store.fail(lease,"CAPTURE_PROCESSING_FAILED"); }
    }
}
