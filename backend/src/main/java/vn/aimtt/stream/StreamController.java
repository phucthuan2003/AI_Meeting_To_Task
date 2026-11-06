package vn.aimtt.stream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vn.aimtt.capture.CaptureService;
import vn.aimtt.job.*;
import vn.aimtt.llm.*;

@RestController
@RequestMapping("/api/v1")
public class StreamController {
    private final SnapshotStreams streams;private final CaptureService captures;private final AnalysisJobService service;
    private final AnalysisJobStore jobs;private final ExtractionValidator validator;private final ObjectMapper json;
    public StreamController(SnapshotStreams streams,CaptureService captures,AnalysisJobService service,AnalysisJobStore jobs,ExtractionValidator validator,ObjectMapper json){
        this.streams=streams;this.captures=captures;this.service=service;this.jobs=jobs;this.validator=validator;this.json=json;
    }
    @GetMapping(value="/captures/{id}/events",produces="text/event-stream")
    public SseEmitter capture(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){return streams.open(jwt,()->captures.view(UUID.fromString(jwt.getSubject()),id));}
    @GetMapping(value="/jobs/{id}/events",produces="text/event-stream")
    public SseEmitter job(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){return streams.open(jwt,()->{
        var view=service.get(UUID.fromString(jwt.getSubject()),id);var job=jobs.get(id);var parsed=new ArrayList<ExtractionValidator.Parsed>();
        for(var row:jobs.chunks(id)) if("COMPLETED".equals(row.status())) {
            try {parsed.addAll(json.readValue(row.outputJson(),new TypeReference<List<ExtractionValidator.Parsed>>(){}));}
            catch(Exception e){throw new IllegalStateException();}
        }
        parsed.sort(Comparator.comparingInt(e->e.event().sequence()));
        return Map.of("job",view,"provisional",parsed.isEmpty()?List.of():validator.reconcile(job,parsed,null,null,0).candidates().stream().map(c->Map.of(
                        "taskRef",c.taskRef(),"taskName",c.taskName(),"assigneeRaw",c.assigneeRaw()==null?"":c.assigneeRaw())).toList());
    });}
}
