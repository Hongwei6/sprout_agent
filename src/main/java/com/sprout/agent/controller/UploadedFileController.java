package com.sprout.agent.controller;

import com.sprout.agent.service.UploadedFileService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/files")
public class UploadedFileController {

    private final UploadedFileService files;

    public UploadedFileController(UploadedFileService files) {
        this.files = files;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadedFileService.UploadResponse upload(@RequestParam("file") MultipartFile file) {
        return files.upload(file);
    }
}
