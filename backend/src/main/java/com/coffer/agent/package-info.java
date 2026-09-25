/**
 * Agent 核心编排层：基于 LangChain4j AiServices 的智能体装配、对话编排。
 *
 * <p>后续规划：
 * <ul>
 *   <li>{@code agent.memory} —— 短期对话记忆（Redis 存储，支持同会话指代消解）；</li>
 *   <li>{@code agent.service} —— Agent 编排核心（工具注册、对话流程控制）。</li>
 * </ul>
 */
package com.coffer.agent;
