package com.refresh.gptChat.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.refresh.gptChat.pojo.*;
import com.refresh.gptChat.service.messageService;
import com.refresh.gptChat.service.outPutService;
import com.refresh.gptChat.service.processService;
import com.refresh.gptChat.service.tokenService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.apache.commons.lang.StringUtils;
import org.json.JSONException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static com.refresh.gptChat.pojo.TokenInfo.getAccess_token;

/**
 * @author Yangyang
 * @create 2023-12-25 18:29
 */

@Slf4j
@RestController()
public class chatController {
    /**
     * 最大上传音频文件 4MB
     */
    public static final long MAX_FILE_SIZE = 4 * 1024 * 1024;
    /**
     * image接口
     */
    private static final String imagePath = "/v1/images/generations";
    /**
     * chat接口
     */
    private static final String chatPath = "/v1/chat/completions";
    /**
     * audio接口
     */
    private static final String audioPath = "/v1/audio/transcriptions";
    /**
     * speech接口
     */
    private static final String speechPath = "/v1/audio/speech";
    /**
     * edit接口
     */
    private static final String editPath = "/v1/images/edits";
    /**
     * utf-8类型
     */
    private static final MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
    /**
     * 定义正则
     */
    private static final Pattern pattern = Pattern.compile("\\(([^)]+)\\)");
    /**
     * 缓存access_token
     */
    private static ConcurrentHashMap<String, List<TokenInfo>> refreshTokenList;
    /**
     * 定义线程池里的线程名字
     */
    private static ThreadFactory threadFactory = new ThreadFactory() {
        private final AtomicInteger counter = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "refreshPool-" + counter.getAndIncrement());
        }
    };
    /**
     * 定义线程池
     */
    private static ExecutorService executor;
    /**
     * 删掉模型前缀 gpt-4-gizmo-
     */
    private static String cancelGizmo = "gpt-4-gizmo-";

    static {
        refreshTokenList = new ConcurrentHashMap<>();
    }

    /**
     * okhttp3 client服务定义
     */
    private final OkHttpClient client = new OkHttpClient.Builder().
            connectTimeout(3, TimeUnit.MINUTES).readTimeout(5, TimeUnit.MINUTES).
            writeTimeout(5, TimeUnit.MINUTES).build();
    /**
     * 最大线程数
     */
    @Value("${max_threads}")
    private int max_threads;
    /**
     * is cancel gpt-4-gizmo
     */
    @Value("${isCancelGizmo}")
    private boolean isCancelGizmo;
    /**
     * 画图使用的模型 gpt-4-mobile 或者 gpt-4
     */
    @Value("${image_mobel}")
    private String image_mobel;
    /**
     * 导入outPutService/messageService/tokenService
     */
    @Autowired
    private outPutService outPutService;
    @Autowired
    private messageService messageService;
    @Autowired
    private tokenService tokenService;
    @Autowired
    private processService processService;

    public static ConcurrentHashMap<String, List<TokenInfo>> getRefreshTokenList() {
        return refreshTokenList;
    }

    public static void setExecutor(Integer maxPoolSize) {
        executor = new ThreadPoolExecutor(0, maxPoolSize, 60L,
                TimeUnit.SECONDS, new SynchronousQueue<>(), threadFactory);
    }

    @PostConstruct
    public void init() {
        setExecutor(max_threads);

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.readTree(new String(Files.readAllBytes(Paths.get("/app/accounts.json"))));
            Iterator<String> fieldNames = json.fieldNames();
            while (fieldNames.hasNext()) {
                String key = fieldNames.next();
                List<TokenInfo> tokensList = new ArrayList<>();
                for (JsonNode node: json.get(key)) {
                    String refreshToken = node.get("refreshToken").asText();
                    String accessToken = node.get("accessToken").asText();
                    String status = node.get("status").asText();
                    String request_id = node.get("request_id").asText();
                    if(!((StringUtils.isEmpty(refreshToken)&&StringUtils.isEmpty(accessToken))||"0".equals(status))){
                        tokensList.add(new TokenInfo(refreshToken, accessToken, request_id, status));
                    }
                }
                refreshTokenList.put(key, tokensList);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        log.info("refreshTokenList初始化成功！");
    }

    @Scheduled(cron = "0 0 0 */3 * ?")
    private void clearModelsUsage() {
        int count = 0;

        for (Map.Entry<String, List<TokenInfo>> entry : refreshTokenList.entrySet()) {
            String key = entry.getKey();
            List<TokenInfo> tokens = entry.getValue();

            for (TokenInfo token : tokens) {
                if(!StringUtils.isEmpty(token.getRefreshToken())){
                    String newAccessToken = tokenService.getAccessToken(token.getRefreshToken());
                    token.setAccessToken(newAccessToken);
                    count++;
                }

            }
        }
        log.info("更新accessToken数：{}",count);
    }

    /**
     * 自定义chat接口
     * 请求体不是json 会报Request body is missing or not in JSON format
     * Authorization token缺失  会报Authorization header is missing
     * 无法请求到access_token 会报refresh_token is wrong
     *
     * @param response
     * @param request
     * @param conversation
     * @return
     * @throws JSONException
     * @throws IOException
     */
    @PostMapping(value = "/v1/chat/completions")
    public ResponseEntity<Object> chatConversation(HttpServletResponse response, HttpServletRequest request, @org.springframework.web.bind.annotation.RequestBody Conversation conversation) {
        String header = request.getHeader("Authorization");
        String authorizationHeader = (header != null && !header.trim().isEmpty()) ? header.trim() : null;
        // 异步处理
        CompletableFuture<ResponseEntity<Object>> future = CompletableFuture.supplyAsync(() -> {
            try {
                if (conversation == null) {
                    return new ResponseEntity<>(Result.error("Request body is missing or not in JSON format"), HttpStatus.BAD_REQUEST);
                }
                if (conversation.getModel().startsWith("gpt-4-gizmo") && isCancelGizmo) {
                    conversation.setModel(conversation.getModel().replace(cancelGizmo, ""));
                }
                String[] result = messageService.extractApiKeyAndRequestUrl(authorizationHeader);
                String key = result[0];
                String request_url = result[1];
                TokenInfo tokenInfo = getAccess_token(key, refreshTokenList, tokenService, null);
                String access_token = tokenInfo.getAccessToken();
                String request_id = tokenInfo.getRequestId();
                Map<String, String> headersMap = tokenService.addHeader(access_token, request_id);
                String json = com.alibaba.fastjson2.JSON.toJSONString(conversation);
                RequestBody requestBody = RequestBody.create(json, mediaType);
                String chatUrl = request_url;
                if (request_url.contains(chatPath)) {
                    chatUrl = request_url.replace(chatPath, "");
                }
                chatUrl = chatUrl + chatPath;
                log.info("请求回复接口：" + chatUrl);
                Request.Builder requestBuilder = new Request.Builder().url(chatUrl).post(requestBody);
                headersMap.forEach(requestBuilder::addHeader);
                Request streamRequest = requestBuilder.build();
                try (Response resp = client.newCall(streamRequest).execute()) {
                    log.info("response_code:{}",resp.code());
                    if (!resp.isSuccessful()) {
                        processService.chatManageUnsuccessfulResponse(refreshTokenList, resp,
                                key, response, conversation, chatUrl,
                                request_id, tokenInfo);
                    } else {
                        // 流式和非流式输出
                        outPutService.outPutChat(response, resp, conversation);
                    }
                } catch (ResponseStatusException e) {
                    return new ResponseEntity<>(e.getMessage(), e.getStatus());
                } catch (Exception e) {
                    return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
                }
            } catch (IllegalArgumentException e) {
                return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
            }
            return null;
        }, executor);
        return outPutService.getObjectResponseEntity(response, future);
    }

    /**
     * 自定义Image接口
     * 请求体不是json 会报Request body is missing or not in JSON format
     * Authorization token缺失  会报Authorization header is missing
     * 无法请求到access_token 会报refresh_token is wrong
     *
     * @param response
     * @param request
     * @param conversation
     * @return
     * @throws JSONException
     * @throws IOException
     */
    @PostMapping(value = "/v1/images/generations")
    public ResponseEntity<Object> imageConversation(HttpServletResponse response,
                                                    HttpServletRequest request,
                                                    @org.springframework.web.bind.annotation.RequestBody Image conversation) {
        String header = request.getHeader("Authorization");
        String authorizationHeader = (header != null && !header.trim().isEmpty()) ? header.trim() : null;
        // 异步处理
        CompletableFuture<ResponseEntity<Object>> future = CompletableFuture.supplyAsync(() -> {
            try {
                String[] result = messageService.extractApiKeyAndRequestUrl(authorizationHeader);
                String key = result[0];
                String request_url = result[1];
                TokenInfo tokenInfo = getAccess_token(key, refreshTokenList, tokenService, null);
                String access_token = tokenInfo.getAccessToken();
                String request_id = tokenInfo.getRequestId();
                Map<String, String> headersMap = tokenService.addHeader(access_token, request_id);
                String imageUrl = request_url;
                // 检查URL是否包含要去除的部分
                if (request_url.contains(chatPath)) {
                    imageUrl = request_url.replace(chatPath, "");
                }
                String json = com.alibaba.fastjson2.JSON.toJSONString(conversation);
                RequestBody requestBody = RequestBody.create(json, mediaType);
                // 去除指定部分
                imageUrl = request_url + imagePath;
                log.info("请求image回复接口：" + imageUrl);
                Request.Builder requestBuilder = new Request.Builder().url(imageUrl).post(requestBody);
                headersMap.forEach(requestBuilder::addHeader);
                Request streamRequest = requestBuilder.build();
                try (Response resp = client.newCall(streamRequest).execute()) {
                    log.info("response_code:{}",resp.code());
                    if (!resp.isSuccessful()) {
                        processService.imageManageUnsuccessfulResponse(refreshTokenList, resp,
                                key, response, conversation, imageUrl,
                                request_id, tokenInfo);
                    } else {
                        // 回复image回答
                        outPutService.outPutImage(response, resp, conversation);
                    }
                } catch (ResponseStatusException e) {
                    return new ResponseEntity<>(e.getMessage(), e.getStatus());
                } catch (Exception e) {
                    return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        }, executor);
        return outPutService.getObjectResponseEntity(response, future);
    }


    /**
     * 自定义speech接口
     * 请求体不是json 会报Request body is missing or not in JSON format
     * Authorization token缺失  会报Authorization header is missing
     * 无法请求到access_token 会报refresh_token is wrong
     *
     * @param response
     * @param request
     * @param conversation
     * @return
     * @throws JSONException
     * @throws IOException
     */
    @PostMapping(value = "/v1/audio/speech")
    public ResponseEntity<Object> audioConversation(HttpServletResponse response,
                                                    HttpServletRequest request,
                                                    @org.springframework.web.bind.annotation.RequestBody Speech conversation) {
        String header = request.getHeader("Authorization");
        String authorizationHeader = (header != null && !header.trim().isEmpty()) ? header.trim() : null;
        // 异步处理
        CompletableFuture<ResponseEntity<Object>> future = CompletableFuture.supplyAsync(() -> {
            try {
                if (conversation == null) {
                    return new ResponseEntity<>(Result.error("Request body is missing or not in JSON format"), HttpStatus.BAD_REQUEST);
                }
                String[] result = messageService.extractApiKeyAndRequestUrl(authorizationHeader);
                String key = result[0];
                String request_url = result[1];
                TokenInfo tokenInfo = getAccess_token(key, refreshTokenList, tokenService, null);
                String access_token = tokenInfo.getAccessToken();
                String request_id = tokenInfo.getRequestId();
                String speechUrl = request_url;
                // 检查URL是否包含要去除的部分
                if (request_url.contains(chatPath)) {
                    speechUrl = request_url.replace(chatPath, "") + speechPath;
                }
                speechUrl = speechUrl + speechPath;
                Map<String, String> headersMap = tokenService.addHeader(access_token, request_id);
                String json = com.alibaba.fastjson2.JSON.toJSONString(conversation);
                RequestBody requestBody = RequestBody.create(json, mediaType);
                log.info("请求speech回复接口：" + speechUrl);
                Request.Builder requestBuilder = new Request.Builder().url(speechUrl).post(requestBody);
                headersMap.forEach(requestBuilder::addHeader);
                Request streamRequest = requestBuilder.build();
                try (Response resp = client.newCall(streamRequest).execute()) {
                    log.info("response_code:{}",resp.code());
                    log.info("response_code:{}",resp.code());
                    if (!resp.isSuccessful()) {
                        processService.speechManageUnsuccessfulResponse(refreshTokenList, resp,
                                key, response, conversation, speechUrl,
                                request_id, tokenInfo);
                    } else {
                        // speech 输出
                        outPutService.outPutSpeech(response, resp, conversation);
                    }
                } catch (ResponseStatusException e) {
                    return new ResponseEntity<>(e.getMessage(), e.getStatus());
                } catch (Exception e) {
                    return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
                }
            } catch (IllegalArgumentException e) {
                return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
            }
            return null;
        }, executor);
        return outPutService.getObjectResponseEntity(response, future);
    }


    /**
     * 自定义audio接口
     * 请求体不是json 会报Request body is missing or not in JSON format
     * Authorization token缺失  会报Authorization header is missing
     * 无法请求到access_token 会报refresh_token is wrong
     *
     * @param response
     * @param request
     * @return
     * @throws JSONException
     * @throws IOException
     */
    @PostMapping(value = "/v1/audio/transcriptions")
    public ResponseEntity<Object> AudioConversation(HttpServletResponse response,
                                                    HttpServletRequest request,
                                                    @RequestPart("file") MultipartFile file,
                                                    @RequestPart("model") String model) {
        if (file.isEmpty()) {
            return new ResponseEntity<>("Missing file", HttpStatus.BAD_REQUEST);
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            return new ResponseEntity<>("File is too large, limit is: " + MAX_FILE_SIZE, HttpStatus.BAD_REQUEST);
        }
        String filename = file.getOriginalFilename();
        log.info("上传文件名：" + filename + " 上传文件名：" + file.getSize());
        log.info("上传模型：" + model);
        if (model == null || model.trim().isEmpty()) {
            return new ResponseEntity<>("Model cannot be empty", HttpStatus.BAD_REQUEST);
        }
        String header = request.getHeader("Authorization");
        String authorizationHeader = (header != null && !header.trim().isEmpty()) ? header.trim() : null;
        CompletableFuture<ResponseEntity<Object>> future =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        String[] result = messageService.extractApiKeyAndRequestUrl(authorizationHeader);
                        String key = result[0];
                        String request_url = result[1];
                        TokenInfo tokenInfo = getAccess_token(key, refreshTokenList, tokenService, null);
                        String access_token = tokenInfo.getAccessToken();
                        String request_id = tokenInfo.getRequestId();
                        String audioUrl = request_url;
                        if (request_url.contains(chatPath)) {
                            audioUrl = request_url.replace(chatPath, "");
                        }
                        audioUrl = audioUrl + audioPath;
                        Map<String, String> headersMap = tokenService.addHeader(access_token, request_id);
                        RequestBody fileBody = RequestBody.create(file.getBytes(),
                                MediaType.parse("application/octet-stream"));
                        log.info("请求speech回复接口：" + audioUrl);
                        RequestBody body = new MultipartBody.Builder()
                                .setType(MultipartBody.FORM)
                                .addFormDataPart("model", model)
                                .addFormDataPart("file", filename, fileBody)
                                .build();
                        Request.Builder requestBuilder = new Request.Builder()
                                .url(audioUrl)
                                .post(body);
                        headersMap.forEach(requestBuilder::addHeader);
                        try (Response resp = client.newCall(requestBuilder.build()).execute()) {
                            log.info(resp.toString());
                            if (!resp.isSuccessful()) {
                                processService.audioManageUnsuccessfulResponse(refreshTokenList, resp,
                                        key, response, fileBody, filename, model,
                                        audioUrl, request_id, tokenInfo);
                            } else {
                                outPutService.outPutAudio(response, resp, model);
                            }
                        }
                    } catch (Exception e) {
                        return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
                    }
                    return null;
                }, executor);
        return outPutService.getObjectResponseEntity(response, future);
    }

    /**
     * 自定义v1/images/edits接口
     * 请求体不是json 会报Request body is missing or not in JSON format
     * Authorization token缺失  会报Authorization header is missing
     * 无法请求到access_token 会报refresh_token is wrong
     *
     * @param response
     * @param request
     * @return
     * @throws JSONException
     * @throws IOException
     */
    @PostMapping(value = "/v1/images/edits")
    public ResponseEntity<Object> AudioConversation(HttpServletResponse response,
                                                    HttpServletRequest request,
                                                    @RequestPart("image") MultipartFile image,
                                                    @RequestPart("mask") MultipartFile mask,
                                                    @RequestPart("prompt") String prompt,
                                                    @RequestPart("n") String n ){
        if (image.isEmpty() || mask.isEmpty()) {
            return new ResponseEntity<>("Missing Image or Mask", HttpStatus.BAD_REQUEST);
        }
        String imageName = image.getOriginalFilename();
        String maskName = mask.getOriginalFilename();
        log.info("上传Image名：" + imageName + " 上传大小：" + image.getSize());
        log.info("上传Mask名：" + maskName + " 上传大小：" + mask.getSize());
        log.info("prompt：" + prompt);
        log.info("n：" + n);
        if (prompt == null || prompt.trim().isEmpty()) {
            return new ResponseEntity<>("prompt cannot be empty ", HttpStatus.BAD_REQUEST);
        }
        if (n == null || Integer.parseInt(n) <= 0) {
            return new ResponseEntity<>("n cannot be empty and n >= 1", HttpStatus.BAD_REQUEST);
        }
        String header = request.getHeader("Authorization");
        String authorizationHeader = (header != null && !header.trim().isEmpty()) ? header.trim() : null;
        CompletableFuture<ResponseEntity<Object>> future =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        String[] result = messageService.extractApiKeyAndRequestUrl(authorizationHeader);
                        String key = result[0];
                        String request_url = result[1];
                        TokenInfo tokenInfo = getAccess_token(key, refreshTokenList, tokenService, null);
                        String access_token = tokenInfo.getAccessToken();
                        String request_id = tokenInfo.getRequestId();
                        String editUrl = request_url;
                        if (request_url.contains(chatPath)) {
                            editUrl = request_url.replace(chatPath, "");
                        }
                        editUrl = editUrl + editPath;
                        Map<String, String> headersMap = tokenService.addHeader(access_token, request_id);
                        RequestBody imageBody = RequestBody.create(image.getBytes(),
                                MediaType.parse("application/octet-stream"));
                        RequestBody maskBody = RequestBody.create(mask.getBytes(),
                                MediaType.parse("application/octet-stream"));
                        log.info("请求image edits 回复接口：" + editUrl);
                        RequestBody body = new MultipartBody.Builder()
                                .setType(MultipartBody.FORM)
                                .addFormDataPart("prompt", prompt)
                                .addFormDataPart("n", n)
                                .addFormDataPart("image", imageName, imageBody)
                                .addFormDataPart("mask", maskName, maskBody)
                                .build();
                        Request.Builder requestBuilder = new Request.Builder()
                                .url(editUrl)
                                .post(body);
                        headersMap.forEach(requestBuilder::addHeader);
                        try (Response resp = client.newCall(requestBuilder.build()).execute()) {
                            log.info(resp.toString());
                            if (!resp.isSuccessful()) {
                                processService.editManageUnsuccessfulResponse(refreshTokenList, resp,
                                        key, response, imageBody,imageName,maskBody,maskName,prompt,n, editUrl,request_id, tokenInfo);
                            } else {
                                outPutService.outPutEdit(response, resp);
                            }
                        }
                    } catch (Exception e) {
                        return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
                    }
                    return null;
                }, executor);
        return outPutService.getObjectResponseEntity(response, future);
    }

}