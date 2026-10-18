package com.coffer.task.api;
import com.coffer.dto.Result;
import com.coffer.file.api.dto.FileDetailResponse;
import com.coffer.task.application.TaskFileLookupService;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/tasks")
public class TaskFileController {
    private final TaskFileLookupService lookup;
    public TaskFileController(TaskFileLookupService lookup) { this.lookup = lookup; }
    @GetMapping("/{taskId}/file")
    public Result<FileDetailResponse> getFile(@PathVariable String taskId) { return Result.success(lookup.find(taskId)); }
}
