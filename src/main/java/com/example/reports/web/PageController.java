package com.example.reports.web;

import com.example.reports.job.JobProperties;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class PageController {

    private final JobProperties properties;

    PageController(JobProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/")
    String index(Model model) {
        model.addAttribute("job", properties);
        return "index";
    }
}
