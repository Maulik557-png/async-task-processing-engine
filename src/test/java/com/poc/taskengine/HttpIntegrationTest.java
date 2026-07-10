package com.poc.taskengine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.taskengine.dto.TaskSubmitRequest;
import com.poc.taskengine.enums.TaskPriority;
import com.poc.taskengine.enums.TaskStatus;
import com.poc.taskengine.enums.TaskType;
import com.poc.taskengine.model.Task;
import com.poc.taskengine.repository.TaskJpaRepository;
import com.poc.taskengine.repository.TaskRepository;
import com.poc.taskengine.service.TaskService;
import com.poc.taskengine.worker.TaskHandler;
import com.poc.taskengine.worker.TaskHandlerRegistry;
import com.poc.taskengine.worker.TaskResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class HttpIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskJpaRepository taskJpaRepository;

    @Autowired
    private TaskHandlerRegistry handlerRegistry;

    @Autowired
    private TaskService taskService;

    private TaskHandler originalHandler;

    @BeforeEach
    public void setUp() {
        taskJpaRepository.deleteAll();
        taskService.resetRateLimiter();
    }

    @AfterEach
    public void tearDown() {
        if (originalHandler != null) {
            handlerRegistry.registerHandler(TaskType.DATA_EXPORT, originalHandler);
        }
    }

    @Test
    public void testSubmitAndPollFlow() throws Exception {
        TaskSubmitRequest request = new TaskSubmitRequest();
        request.setType(TaskType.DATA_EXPORT);
        request.setPriority(TaskPriority.NORMAL);
        request.setPayload("{}");
        request.setSubmittedBy("http-user");
        request.setMaxRetries(3);

        String json = objectMapper.writeValueAsString(request);

        MvcResult result = mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isAccepted())
                .andReturn();

        String responseJson = result.getResponse().getContentAsString();
        String taskId = objectMapper.readTree(responseJson).get("taskId").asText();
        assertThat(taskId).isNotBlank();

        long deadline = System.currentTimeMillis() + 15000;
        TaskStatus finalStatus = null;
        while (System.currentTimeMillis() < deadline) {
            MvcResult pollResult = mockMvc.perform(get("/api/v1/tasks/{id}", taskId))
                    .andExpect(status().isOk())
                    .andReturn();
            String pollJson = pollResult.getResponse().getContentAsString();
            String statusStr = objectMapper.readTree(pollJson).get("status").asText();
            finalStatus = TaskStatus.valueOf(statusStr);
            if (finalStatus == TaskStatus.COMPLETED || finalStatus == TaskStatus.FAILED || finalStatus == TaskStatus.TIMED_OUT) {
                break;
            }
            Thread.sleep(200);
        }

        assertThat(finalStatus).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    public void test503AtSemaphoreLimit() throws Exception {
        originalHandler = handlerRegistry.getHandler(TaskType.DATA_EXPORT);

        CountDownLatch blockLatch = new CountDownLatch(1);

        TaskHandler blockingHandler = new TaskHandler() {
            @Override
            public TaskType getSupportedType() {
                return TaskType.DATA_EXPORT;
            }

            @Override
            public TaskResult execute(Task task) {
                try {
                    blockLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new TaskResult("Success");
            }
        };
        handlerRegistry.registerHandler(TaskType.DATA_EXPORT, blockingHandler);

        TaskSubmitRequest request = new TaskSubmitRequest();
        request.setType(TaskType.DATA_EXPORT);
        request.setPriority(TaskPriority.LOW);
        request.setPayload("{}");
        request.setSubmittedBy("semaphore-flood");
        request.setMaxRetries(0);

        String json = objectMapper.writeValueAsString(request);

        for (int i = 0; i < 50; i++) {
            mockMvc.perform(post("/api/v1/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isAccepted());
        }

        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"));

        blockLatch.countDown();
    }

    @Test
    public void testIdempotencyBehaviorOverHttp() throws Exception {
        String idempotencyKey = "idemp-http-" + UUID.randomUUID();

        TaskSubmitRequest request = new TaskSubmitRequest();
        request.setType(TaskType.DATA_EXPORT);
        request.setPriority(TaskPriority.NORMAL);
        request.setPayload("{}");
        request.setSubmittedBy("idemp-user");
        request.setMaxRetries(3);
        request.setIdempotencyKey(idempotencyKey);

        String json = objectMapper.writeValueAsString(request);

        MvcResult r1 = mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isAccepted())
                .andReturn();
        String taskId1 = objectMapper.readTree(r1.getResponse().getContentAsString()).get("taskId").asText();

        MvcResult r2 = mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isAccepted())
                .andReturn();
        String taskId2 = objectMapper.readTree(r2.getResponse().getContentAsString()).get("taskId").asText();

        assertThat(taskId1).isEqualTo(taskId2);
    }

    @Test
    public void testConcurrentDuplicateIdempotencyRacesOverHttp() throws Exception {
        String idempotencyKey = "idemp-race-http-" + UUID.randomUUID();

        TaskSubmitRequest request = new TaskSubmitRequest();
        request.setType(TaskType.DATA_EXPORT);
        request.setPriority(TaskPriority.NORMAL);
        request.setPayload("{}");
        request.setSubmittedBy("race-user");
        request.setMaxRetries(3);
        request.setIdempotencyKey(idempotencyKey);

        String json = objectMapper.writeValueAsString(request);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        List<String> results = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successCounter = new AtomicInteger(0);

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    MvcResult mvcResult = mockMvc.perform(post("/api/v1/tasks")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(json))
                            .andReturn();
                    int status = mvcResult.getResponse().getStatus();
                    if (status == 200 || status == 202) {
                        String body = mvcResult.getResponse().getContentAsString();
                        String taskId = objectMapper.readTree(body).get("taskId").asText();
                        results.add(taskId);
                        successCounter.incrementAndGet();
                    }
                } catch (Exception e) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        assertThat(successCounter.get()).isEqualTo(2);
        assertThat(results).hasSize(2);
        assertThat(results.get(0)).isEqualTo(results.get(1));

        List<Task> tasks = taskRepository.findAll();
        long matches = tasks.stream()
                .filter(t -> idempotencyKey.equals(t.getIdempotencyKey()))
                .count();
        assertThat(matches).isEqualTo(1L);
    }
}
