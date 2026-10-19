package app.dodb.smd.monitor.hub.spring;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.http.CacheControl.noStore;
import static org.springframework.http.MediaType.parseMediaType;
import static org.springframework.http.ResponseEntity.notFound;
import static org.springframework.http.ResponseEntity.ok;

@RestController
class MonitorHubPage {

    @GetMapping(value = "/", produces = "text/html")
    ResponseEntity<Resource> index() {
        return resource("index.html", MediaType.TEXT_HTML);
    }

    @GetMapping("/app.js")
    ResponseEntity<Resource> script() {
        return resource("app.js", parseMediaType("text/javascript"));
    }

    @GetMapping("/style.css")
    ResponseEntity<Resource> stylesheet() {
        return resource("style.css", parseMediaType("text/css"));
    }

    private ResponseEntity<Resource> resource(String name, MediaType type) {
        var resource = new ClassPathResource("smd-monitor/" + name);
        return resource.exists() ? ok().cacheControl(noStore()).contentType(type).body(resource) : notFound().build();
    }
}
