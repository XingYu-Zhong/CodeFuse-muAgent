/**
 * Alipay.com Inc.
 * Copyright (c) 2004-2024 All Rights Reserved.
 */
package com.alipay.muagent.service.chat.impl;

import com.alipay.muagent.model.chat.ChatRequest;
import com.alipay.muagent.model.chat.ChatResponse;
import com.alipay.muagent.model.chat.content.RoleResponseContent;
import com.alipay.muagent.model.chat.content.TextContent;
import com.alipay.muagent.model.ekg.EkgAlgorithmResult;
import com.alipay.muagent.model.ekg.EkgNode;
import com.alipay.muagent.model.ekg.EkgQueryRequest;
import com.alipay.muagent.model.ekg.EkgQuestionDescription;
import com.alipay.muagent.model.ekg.EkgRequest;
import com.alipay.muagent.model.ekg.EkgResponse;
import com.alipay.muagent.model.ekg.EkgResponseResultMap;
import com.alipay.muagent.model.ekg.EkgToolResponse;
import com.alipay.muagent.model.ekg.ExeNodeResponse;
import com.alipay.muagent.model.ekg.configuration.Config;
import com.alipay.muagent.model.enums.ekg.ToolPlanTypeEnum;
import com.alipay.muagent.model.enums.scheduler.TaskSchedulerTypeEnum;
import com.alipay.muagent.model.scheduler.SubmitTaskRequest;
import com.alipay.muagent.model.scheduler.TaskExeResponse;
import com.alipay.muagent.service.chat.ChatService;
import com.alipay.muagent.service.sheduler.Scheduler;
import com.alipay.muagent.service.sheduler.SchedulerManager;
import com.alipay.muagent.util.GsonUtils;
import com.alipay.muagent.util.HttpUtil;
import com.alipay.muagent.util.LoggerUtil;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gson.JsonObject;
import io.micrometer.common.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.alipay.muagent.model.chat.content.RoleResponseContent.buildLijing;
import static com.alipay.muagent.model.chat.content.RoleResponseContent.buildReferee;
import static com.alipay.muagent.model.chat.content.RoleResponseContent.buildWangpeng;
import static com.alipay.muagent.model.chat.content.RoleResponseContent.buildZhangwei;
import static com.alipay.muagent.model.enums.chat.ChatExtendedKeyEnum.CHAT_UNIQUE_ID;
import static com.alipay.muagent.model.enums.chat.ChatExtendedKeyEnum.EKG_NODE;

/**
 * @author renmao.rm
 * @version : ChatServiceImpl.java, v 0.1 2024年10月10日 上午10:26 renmao.rm Exp $
 */
@Service("ekgChatServiceImpl")
public class EkgChatServiceImpl implements ChatService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EkgChatServiceImpl.class);

    @Autowired
    private Config config;

    @Override
    public void chat(SseEmitter emitter, ChatRequest request) {
        boolean wait = true;

        try {
            TextContent content = GsonUtils.fromString(TextContent.class, GsonUtils.toString(request.getContent()));

            EkgQueryRequest ekgRequest = new EkgQueryRequest();
            Map<String, Object> rqExCtx = request.getExtendContext();
            // 从上下文中获取是否有 ekgNode，如果 ekgNode 存在，表明存在上下文
            if (!CollectionUtils.isEmpty(rqExCtx) && rqExCtx.containsKey(EKG_NODE.name())) {
                String nodeStr = (String) rqExCtx.get(EKG_NODE.name());
                EkgNode node = GsonUtils.fromString(EkgNode.class, nodeStr);
                EkgToolResponse toolResponse = EkgToolResponse.builder()
                        .toolResponse(content.getText())
                        .build();
                ekgRequest.setCurrentNodeId(node.getCurrentNodeId());
                ekgRequest.setObservation(GsonUtils.toString(toolResponse));
                ekgRequest.setType(node.getType());
                if (ToolPlanTypeEnum.USER_PROBLEM.getCode().equals(node.getType())) {
                    ekgRequest.setUserAnswer(GsonUtils.toString(toolResponse));
                }
                Assert.isTrue(rqExCtx.containsKey(CHAT_UNIQUE_ID.name()), "CHAT_UNIQUE_ID is required in ");
                ekgRequest.setSessionId(rqExCtx.get(CHAT_UNIQUE_ID.name()).toString());
            } else {
                // 没有上下文，初始化一个请求
                JsonObject observation = new JsonObject();
                observation.addProperty("content", content.getText());
                ekgRequest.setObservation(observation.toString());
                ekgRequest.setSessionId(UUID.randomUUID().toString().replaceAll("-", ""));
                ekgRequest.setStartRootNodeId("ekg_team_default");
                ekgRequest.setIntentionData(Lists.newArrayList(content.getText()));
            }

            ekgRequest.setIntentionRule(Lists.newArrayList("nlp"));
            ekgRequest.setScene("NEXA");

            while (wait) {

                EkgAlgorithmResult response = sendRequestToEkgPlanner(ekgRequest);

                if (Objects.isNull(response)) {
                    emitter.send(SseEmitter.event().data(GsonUtils.toString(ChatResponse.buildTextResponses("图谱执行异常"))));
                    LoggerUtil.warn(LOGGER, "图谱执行异常");
                    break;
                }

                if (StringUtils.isNotBlank(response.getSummary())) {
                    emitter.send(SseEmitter.event().data(GsonUtils.toString(ChatResponse.buildTextResponses(response.getSummary()))));
                    LoggerUtil.info(LOGGER, "游戏结束");
                    break;
                }

                // 需要给用户反馈信息
                if (StringUtils.isNotBlank(response.getUserInteraction())) {
                    RoleResponseContent rrc = buildRoleResponseContent(response.getUserInteraction());
                    String userMsg = GsonUtils.toString(ChatResponse.buildRoleResponses(rrc));
                    LoggerUtil.info(LOGGER, "notifyUser:{}", userMsg);
                    emitter.send(SseEmitter.event().data(userMsg));
                }

                EkgNode node = response.getToolPlan().get(0);

                // 需要等待用户回答，通知用户后结束轮询
                if (ToolPlanTypeEnum.USER_PROBLEM.getCode().equals(node.getType())) {
                    EkgQuestionDescription ques = node.getQuestionDescription();
                    RoleResponseContent rrc = buildRoleResponseContent("<font color='#A9192d'> *请回答：* </font> \n\n >" + ques.getQuestionContent().getQuestion());

                    List<ChatResponse> crs = ChatResponse.buildRoleResponses(rrc);

                    // 将 node 和 对话id 放入上下文
                    HashMap<String, Object> exContext = Maps.newHashMap();
                    exContext.put(EKG_NODE.name(), GsonUtils.toString(node));
                    exContext.put(CHAT_UNIQUE_ID.name(), ekgRequest.getSessionId());
                    crs.get(0).setExtendContext(exContext);

                    String userMsg = GsonUtils.toString(crs);
                    LoggerUtil.info(LOGGER, "notifyUser:{}", userMsg);
                    emitter.send(SseEmitter.event().data(userMsg));
                    break;
                }

                // 执行当前 node
                ExeNodeResponse enr = executeNode(node);

                EkgToolResponse toolResponse = EkgToolResponse.builder()
                        .toolKey(enr.getToolKey())
                        .toolResponse(enr.getOutput())
                        .toolParam(node.getToolDescription())
                        .build();
                ekgRequest.setCurrentNodeId(node.getCurrentNodeId());
                ekgRequest.setObservation(GsonUtils.toString(toolResponse));
                ekgRequest.setType(node.getType());
                ekgRequest.setIntentionData(null);
                ekgRequest.setIntentionRule(null);
            }
        } catch (Exception e) {
            LoggerUtil.error(LOGGER, e, "[EkgServiceImpl call error][{}][{}][{}][{}]", request.getChatUniqueId());
        }
    }

    private RoleResponseContent buildRoleResponseContent(String userInteraction) {

        if (userInteraction.contains("**王鹏:**")) {
            return buildWangpeng(userInteraction.replace("**王鹏:**  <br>", ""));
        } else if (userInteraction.contains("**李静:**")) {
            return buildLijing(userInteraction.replace("**李静:** <br>", ""));
        } else if (userInteraction.contains("**张伟:**")) {
            return buildZhangwei(userInteraction.replace("**张伟:** <br>", ""));
        }

        return buildReferee(userInteraction);
    }

    @Autowired
    private SchedulerManager schedulerManager;

    private ExeNodeResponse executeNode(EkgNode node) {
        SubmitTaskRequest request = SubmitTaskRequest.builder().intention(node.getToolDescription()).build();
        request.setMemory(node.getMemory());
        Scheduler scheduler = schedulerManager.getScheduler(TaskSchedulerTypeEnum.COMMON);
        request.setTools(config.getToolKeys());
        TaskExeResponse toolExeResponse = scheduler.submitTask(request);

        ExeNodeResponse exeNodeResponse = ExeNodeResponse.builder()
                .output(toolExeResponse.getResponse())
                .toolKey(toolExeResponse.getToolKey())
                .build();
        exeNodeResponse.setOutput(toolExeResponse.getResponse());
        return exeNodeResponse;
    }

    @Override
    public EkgAlgorithmResult sendRequestToEkgPlanner(EkgQueryRequest queryRequest) {

        // Portal来的 走新版EKG推理
        Map<String, String> headers = new HashMap<>(2);
        headers.put("Content-type", "application/json;charset=utf-8");
        headers.put("MPS-app-name", "test");
        headers.put("MPS-http-version", "1.0");
        String responseBody = null;
        Long startTime = System.currentTimeMillis();

        EkgRequest ekgRequest = new EkgRequest(queryRequest);
        String requestJson = GsonUtils.toString(ekgRequest);

        String algoErr = "-";
        String sysErr = "-";
        EkgAlgorithmResult ret = null;
        try {
            try {
                LoggerUtil.info(LOGGER, "[EkgServiceImpl call][{}][{}]", config.getEkgUrl(), requestJson.toString());
                responseBody = HttpUtil.callWithHeadersAndQuery(config.getEkgUrl(), requestJson.toString(), headers, null, "POST");
                LoggerUtil.info(LOGGER, "[EkgServiceImpl call success][{}][{}]", responseBody, System.currentTimeMillis() - startTime);
            } catch (Exception e) {
                sysErr = "ALGO_INVOKE_ERR";
                LoggerUtil.error(LOGGER, e, "[EkgServiceImpl call error][{}][{}][{}][{}]", responseBody, queryRequest.getSessionId());
                return null;
            }
            if (StringUtils.isEmpty(responseBody)) {
                sysErr = "ALGO_RESP_NULL";
                LoggerUtil.error(LOGGER, "[EkgServiceImpl res is null][{}][{}][{}]", requestJson, queryRequest.getSessionId());
                return null;
            }

            try {
                EkgResponse ekgResponse = GsonUtils.fromString(EkgResponse.class, responseBody);
                EkgResponseResultMap resultMap = ekgResponse.getResultMap();
                if (Objects.isNull(resultMap) || StringUtils.isEmpty(resultMap.getAlgorithmResult())) {
                    throw new RuntimeException("ALGO_RESP_INVALID");
                }
                return GsonUtils.fromString(EkgAlgorithmResult.class, resultMap.getAlgorithmResult());
            } catch (Exception e) {
                sysErr = "ALGO_RESP_INVALID";
                LoggerUtil.error(LOGGER, "[EkgServiceImpl parse res error][{}][{}][{}][{}]", requestJson, queryRequest.getSessionId());
                return null;
            }
        } finally {
            LoggerUtil.info(LOGGER, "[EkgServiceImpl][ekgDigest]" + String.join("^", new String[] {
                    ret != null ? "Y" : "N",
                    String.valueOf(System.currentTimeMillis() - startTime),
                    queryRequest.getSessionId(),
                    algoErr,
                    sysErr,
                    // 为保证日志兼容(监控采集分割的左起右至规则), 最后必须有个空白
                    ""
            }));
        }
    }
}