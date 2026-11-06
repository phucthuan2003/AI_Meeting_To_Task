package vn.aimtt.capture;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import vn.aimtt.common.ApiException;

@RestController
@RequestMapping("/api/v1/captures")
public class CaptureController {
    private final CaptureService service;private final AudioProperties properties;
    public CaptureController(CaptureService service, AudioProperties properties) {this.service=service;this.properties=properties;}
    @GetMapping("/config") public Map<String,Object> config() {return Map.of("sttReady",properties.configured(),"maxFileBytes",AudioInput.MAX_BYTES,"maxLiveChunks",480,"maxLiveHours",2);}
    @GetMapping public List<Map<String,Object>> list(@AuthenticationPrincipal Jwt jwt){return service.list(owner(jwt));}
    @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String,Object> create(@AuthenticationPrincipal Jwt jwt,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key) {
        UUID id=service.create(owner(jwt),body,key); return service.view(owner(jwt),id);
    }
    @GetMapping("/{id}") public Map<String,Object> get(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){return service.view(owner(jwt),id);}
    @PostMapping("/{id}/chunks/{sequence}") @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String,Object> upload(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@PathVariable int sequence,
                                    @RequestParam long startMs,@RequestParam("file") MultipartFile file) throws java.io.IOException {
        service.owned(owner(jwt),id);
        if(file.getSize()>AudioInput.MAX_BYTES) throw ApiException.invalid("Audio vượt 10 MiB.");
        service.upload(owner(jwt),id,sequence,startMs,file.getOriginalFilename(),file.getInputStream().readNBytes(AudioInput.MAX_BYTES+1));
        return service.view(owner(jwt),id);
    }
    @PostMapping("/{id}/stop") public Map<String,Object> stop(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestBody JsonNode body){
        if(!body.path("chunkCount").isInt()) throw ApiException.invalid("Thiếu chunkCount.");
        service.stop(owner(jwt),id,body.path("chunkCount").intValue());return service.view(owner(jwt),id);
    }
    @PostMapping("/{id}/cancel") public Map<String,Object> cancel(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){service.cancel(owner(jwt),id);return service.view(owner(jwt),id);}
    @PostMapping("/{id}/retry") public Map<String,Object> retry(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){service.retry(owner(jwt),id);return service.view(owner(jwt),id);}
    @PatchMapping("/{id}/drafts") public Map<String,Object> edit(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestBody JsonNode body){service.override(owner(jwt),id,body);return service.view(owner(jwt),id);}
    @PostMapping("/{id}/publish") public Map<String,Object> publish(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){return Map.of("meetingId",service.publish(owner(jwt),id));}
    private UUID owner(Jwt jwt){return UUID.fromString(jwt.getSubject());}
}
