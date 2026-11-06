package vn.aimtt.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vn.aimtt.auth.SessionRepository;
import vn.aimtt.capture.AudioInput;
import vn.aimtt.common.ApiException;
import org.springframework.http.HttpStatus;

/** SSE is a reconnectable view of durable state. It never owns a job or its transcript. */
@Component
public class SnapshotStreams {
    private final ScheduledExecutorService scheduler=Executors.newScheduledThreadPool(2,r->{var t=new Thread(r,"snapshot-sse");t.setDaemon(true);return t;});
    private final Set<SseEmitter> emitters=ConcurrentHashMap.newKeySet();
    private final Map<UUID,Integer> perOwner=new HashMap<>();
    private final ObjectMapper json; private final SessionRepository sessions;
    public SnapshotStreams(ObjectMapper json,SessionRepository sessions){this.json=json;this.sessions=sessions;}
    public SseEmitter open(Jwt jwt,Supplier<?> snapshot) {
        snapshot.get(); // owner/existence check before sending a successful stream response
        UUID owner=UUID.fromString(jwt.getSubject()),session=UUID.fromString(jwt.getId());
        synchronized(perOwner){
            if(emitters.size()>=64||perOwner.getOrDefault(owner,0)>=3) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"STREAM_LIMIT","Đóng panel thừa rồi thử lại.");
            perOwner.merge(owner,1,Integer::sum);
        }
        var emitter=new SseEmitter(60000L);emitters.add(emitter);
        var previous=new String[]{""}; var ticks=new int[]{0}; var task=new ScheduledFuture<?>[1];
        var closed=new java.util.concurrent.atomic.AtomicBoolean();
        Runnable cleanup=()->{
            if(!closed.compareAndSet(false,true))return;
            emitters.remove(emitter);synchronized(perOwner){perOwner.computeIfPresent(owner,(k,v)->v<=1?null:v-1);}
            if(task[0]!=null)task[0].cancel(false);
        };
        emitter.onCompletion(cleanup);emitter.onTimeout(()->{cleanup.run();emitter.complete();});emitter.onError(e->cleanup.run());
        task[0]=scheduler.scheduleWithFixedDelay(()->{
            if(closed.get())return;
            try {
                if(jwt.getExpiresAt()==null||!jwt.getExpiresAt().isAfter(Instant.now())||sessions.findById(session).filter(s->s.validFor(owner,Instant.now())).isEmpty()) {
                    emitter.send(SseEmitter.event().name("auth-expired").data("{}"));cleanup.run();emitter.complete();return;
                }
                String value=json.writeValueAsString(snapshot.get());
                if(value.length()>2000000)throw new IllegalStateException();
                String id=AudioInput.hash(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if(!id.equals(previous[0])){emitter.send(SseEmitter.event().id(id).name("snapshot").data(value));previous[0]=id;}
                else if(++ticks[0]%10==0)emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch(Exception e){cleanup.run();emitter.complete();}
        },100,1000,TimeUnit.MILLISECONDS);
        if(closed.get())task[0].cancel(false);
        return emitter;
    }
    @PreDestroy public void close(){emitters.forEach(SseEmitter::complete);scheduler.shutdownNow();}
}
