package vn.aimtt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MeetingToTaskApplication {
    public static void main(String[] args) {
        SpringApplication.run(MeetingToTaskApplication.class, args);
    }
}
