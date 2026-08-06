package com.mianmianshi.platform.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.mianmianshi.platform.model.dto.codesubmission.CodeExecutionResult;
import com.mianmianshi.platform.model.entity.CodeSubmission;
import com.mianmianshi.platform.model.entity.Question;
import com.mianmianshi.platform.model.enums.CodeSubmissionStatusEnum;
import com.mianmianshi.platform.mapper.CodeSubmissionMapper;
import com.mianmianshi.platform.service.CodeSubmissionService;
import com.mianmianshi.platform.service.QuestionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 代码提交服务实现 - 负责编译运行用户提交的代码并返回判题结果
 */
@Service
@Slf4j
public class CodeSubmissionServiceImpl implements CodeSubmissionService {

    // 代码执行超时时间（秒）
    private static final long EXECUTION_TIMEOUT = 10;
    // 临时文件目录
    private static final String TMP_DIR = System.getProperty("java.io.tmpdir") + File.separator + "mianmianshi-code";

    @Resource
    private CodeSubmissionMapper codeSubmissionMapper;

    @Resource
    private QuestionService questionService;

    @Override
    @Transactional
    public CodeExecutionResult submitAndJudge(CodeSubmission submission, Long userId) {
        // 1. 查询题目信息
        Question question = questionService.getById(submission.getQuestionId());
        if (question == null) {
            CodeExecutionResult result = new CodeExecutionResult();
            result.setAllPassed(false);
            result.setStatus(CodeSubmissionStatusEnum.RUNTIME_ERROR.getValue());
            result.setErrorMessage("题目不存在");
            return result;
        }

        // 2. 保存提交记录
        submission.setUserId(userId);
        submission.setStatus(CodeSubmissionStatusEnum.RUNNING.getValue());
        codeSubmissionMapper.insert(submission);

        // 3. 解析测试用例
        List<JSONObject> testCaseList = parseTestCases(question.getTestCases());
        if (testCaseList.isEmpty()) {
            saveSubmissionStatus(submission, CodeSubmissionStatusEnum.ACCEPTED, 0, 0, null);
            CodeExecutionResult result = new CodeExecutionResult();
            result.setAllPassed(true);
            result.setStatus(CodeSubmissionStatusEnum.ACCEPTED.getValue());
            result.setPassedCases(0);
            result.setTotalCases(0);
            return result;
        }

        // 4. 执行代码并判题
        CodeExecutionResult result;
        if ("java".equalsIgnoreCase(submission.getLanguage())) {
            result = executeJavaCode(submission.getCode(), testCaseList);
        } else if ("python".equalsIgnoreCase(submission.getLanguage())) {
            result = executePythonCode(submission.getCode(), testCaseList);
        } else {
            result = new CodeExecutionResult();
            result.setAllPassed(false);
            result.setStatus(CodeSubmissionStatusEnum.RUNTIME_ERROR.getValue());
            result.setErrorMessage("暂不支持该编程语言: " + submission.getLanguage());
        }

        // 5. 更新提交记录
        saveSubmissionStatus(submission, result.getStatus(), result.getPassedCases(),
                testCaseList.size(), result);

        // 6. 更新题目统计数据
        updateQuestionStats(question, submission.getQuestionId(), result.getAllPassed());

        return result;
    }

    /**
     * 执行 Java 代码
     */
    private CodeExecutionResult executeJavaCode(String code, List<JSONObject> testCases) {
        CodeExecutionResult result = new CodeExecutionResult();
        result.setTotalCases(testCases.size());

        // 提取类名
        String className = extractJavaClassName(code);
        if (className == null) {
            result.setStatus(CodeSubmissionStatusEnum.COMPILE_ERROR.getValue());
            result.setErrorMessage("无法识别类名，请确保代码包含 public class 定义");
            result.setAllPassed(false);
            result.setPassedCases(0);
            return result;
        }

        Path workDir = null;
        try {
            // 创建临时工作目录
            workDir = Paths.get(TMP_DIR, UUID.randomUUID().toString());
            Files.createDirectories(workDir);

            // 写入 Java 源文件
            Path sourceFile = workDir.resolve(className + ".java");
            Files.write(sourceFile, code.getBytes());

            // 编译
            ProcessBuilder compilePb = new ProcessBuilder("javac", sourceFile.toString());
            compilePb.directory(workDir.toFile());
            compilePb.redirectErrorStream(true);
            Process compileProcess = compilePb.start();
            boolean compileFinished = compileProcess.waitFor(30, TimeUnit.SECONDS);

            if (!compileFinished || compileProcess.exitValue() != 0) {
                String compileError = readProcessOutput(compileProcess);
                result.setStatus(CodeSubmissionStatusEnum.COMPILE_ERROR.getValue());
                result.setErrorMessage("编译错误:\n" + compileError);
                result.setAllPassed(false);
                result.setPassedCases(0);
                return result;
            }

            // 执行每个测试用例
            long totalTimeMs = 0;
            int passedCount = 0;
            List<CodeExecutionResult.TestCaseResult> caseResults = new ArrayList<>();

            for (int i = 0; i < testCases.size(); i++) {
                JSONObject testCase = testCases.get(i);
                String input = testCase.getStr("input", "");
                String expectedOutput = testCase.getStr("expectedOutput", "").trim();

                CodeExecutionResult.TestCaseResult caseResult = new CodeExecutionResult.TestCaseResult();
                caseResult.setIndex(i + 1);
                caseResult.setInput(input);
                caseResult.setExpectedOutput(expectedOutput);

                try {
                    long startTime = System.currentTimeMillis();
                    ProcessBuilder runPb = new ProcessBuilder("java", "-cp", workDir.toString(), className);
                    runPb.directory(workDir.toFile());
                    runPb.redirectErrorStream(true);
                    Process runProcess = runPb.start();

                    // 写入输入
                    if (input != null && !input.isEmpty()) {
                        try (OutputStream os = runProcess.getOutputStream()) {
                            os.write(input.getBytes());
                            os.flush();
                        }
                    }

                    boolean finished = runProcess.waitFor(EXECUTION_TIMEOUT, TimeUnit.SECONDS);
                    long execTime = System.currentTimeMillis() - startTime;
                    totalTimeMs += execTime;
                    caseResult.setExecuteTimeMs(execTime);

                    if (!finished) {
                        runProcess.destroyForcibly();
                        caseResult.setPassed(false);
                        caseResult.setActualOutput("执行超时 (" + EXECUTION_TIMEOUT + "s)");
                        result.setStatus(CodeSubmissionStatusEnum.TIME_LIMIT_EXCEEDED.getValue());
                    } else {
                        String actualOutput = readProcessOutput(runProcess).trim();
                        caseResult.setActualOutput(actualOutput);

                        if (actualOutput.equals(expectedOutput)) {
                            caseResult.setPassed(true);
                            passedCount++;
                        } else {
                            caseResult.setPassed(false);
                        }
                    }
                } catch (Exception e) {
                    caseResult.setPassed(false);
                    caseResult.setActualOutput("运行异常: " + e.getMessage());
                }

                caseResults.add(caseResult);
            }

            result.setTestCaseResults(caseResults);
            result.setPassedCases(passedCount);
            result.setExecuteTimeMs(totalTimeMs);
            result.setAllPassed(passedCount == testCases.size());

            if (result.getAllPassed()) {
                result.setStatus(CodeSubmissionStatusEnum.ACCEPTED.getValue());
            } else if (result.getStatus() == null ||
                    !result.getStatus().equals(CodeSubmissionStatusEnum.TIME_LIMIT_EXCEEDED.getValue())) {
                result.setStatus(CodeSubmissionStatusEnum.WRONG_ANSWER.getValue());
            }

        } catch (Exception e) {
            log.error("代码执行异常", e);
            result.setStatus(CodeSubmissionStatusEnum.RUNTIME_ERROR.getValue());
            result.setErrorMessage("系统执行异常: " + e.getMessage());
            result.setAllPassed(false);
            result.setPassedCases(0);
        } finally {
            // 清理临时文件
            if (workDir != null) {
                try {
                    deleteDirectory(workDir);
                } catch (IOException ignored) {
                }
            }
        }

        return result;
    }

    /**
     * 执行 Python 代码
     */
    private CodeExecutionResult executePythonCode(String code, List<JSONObject> testCases) {
        CodeExecutionResult result = new CodeExecutionResult();
        result.setTotalCases(testCases.size());

        Path workDir = null;
        try {
            workDir = Paths.get(TMP_DIR, UUID.randomUUID().toString());
            Files.createDirectories(workDir);

            // 写入 Python 源文件
            Path sourceFile = workDir.resolve("solution.py");
            Files.write(sourceFile, code.getBytes());

            long totalTimeMs = 0;
            int passedCount = 0;
            List<CodeExecutionResult.TestCaseResult> caseResults = new ArrayList<>();

            for (int i = 0; i < testCases.size(); i++) {
                JSONObject testCase = testCases.get(i);
                String input = testCase.getStr("input", "");
                String expectedOutput = testCase.getStr("expectedOutput", "").trim();

                CodeExecutionResult.TestCaseResult caseResult = new CodeExecutionResult.TestCaseResult();
                caseResult.setIndex(i + 1);
                caseResult.setInput(input);
                caseResult.setExpectedOutput(expectedOutput);

                try {
                    long startTime = System.currentTimeMillis();
                    ProcessBuilder runPb = new ProcessBuilder("python", sourceFile.toString());
                    runPb.directory(workDir.toFile());
                    runPb.redirectErrorStream(true);
                    Process runProcess = runPb.start();

                    if (input != null && !input.isEmpty()) {
                        try (OutputStream os = runProcess.getOutputStream()) {
                            os.write(input.getBytes());
                            os.flush();
                        }
                    }

                    boolean finished = runProcess.waitFor(EXECUTION_TIMEOUT, TimeUnit.SECONDS);
                    long execTime = System.currentTimeMillis() - startTime;
                    totalTimeMs += execTime;
                    caseResult.setExecuteTimeMs(execTime);

                    if (!finished) {
                        runProcess.destroyForcibly();
                        caseResult.setPassed(false);
                        caseResult.setActualOutput("执行超时 (" + EXECUTION_TIMEOUT + "s)");
                        result.setStatus(CodeSubmissionStatusEnum.TIME_LIMIT_EXCEEDED.getValue());
                    } else {
                        String actualOutput = readProcessOutput(runProcess).trim();
                        caseResult.setActualOutput(actualOutput);

                        if (actualOutput.equals(expectedOutput)) {
                            caseResult.setPassed(true);
                            passedCount++;
                        } else {
                            caseResult.setPassed(false);
                        }
                    }
                } catch (Exception e) {
                    caseResult.setPassed(false);
                    caseResult.setActualOutput("运行异常: " + e.getMessage());
                }

                caseResults.add(caseResult);
            }

            result.setTestCaseResults(caseResults);
            result.setPassedCases(passedCount);
            result.setExecuteTimeMs(totalTimeMs);
            result.setAllPassed(passedCount == testCases.size());

            if (result.getAllPassed()) {
                result.setStatus(CodeSubmissionStatusEnum.ACCEPTED.getValue());
            } else if (result.getStatus() == null ||
                    !result.getStatus().equals(CodeSubmissionStatusEnum.TIME_LIMIT_EXCEEDED.getValue())) {
                result.setStatus(CodeSubmissionStatusEnum.WRONG_ANSWER.getValue());
            }

        } catch (Exception e) {
            log.error("Python代码执行异常", e);
            result.setStatus(CodeSubmissionStatusEnum.RUNTIME_ERROR.getValue());
            result.setErrorMessage("系统执行异常: " + e.getMessage());
            result.setAllPassed(false);
            result.setPassedCases(0);
        } finally {
            if (workDir != null) {
                try {
                    deleteDirectory(workDir);
                } catch (IOException ignored) {
                }
            }
        }

        return result;
    }

    /**
     * 解析测试用例 JSON 字符串
     */
    private List<JSONObject> parseTestCases(String testCasesJson) {
        if (testCasesJson == null || testCasesJson.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            JSONArray array = JSONUtil.parseArray(testCasesJson);
            List<JSONObject> result = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                result.add(array.getJSONObject(i));
            }
            return result;
        } catch (Exception e) {
            log.error("解析测试用例失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 提取 Java 类名
     */
    private String extractJavaClassName(String code) {
        String[] lines = code.split("\n");
        for (String line : lines) {
            line = line.trim();
            int classIdx = line.indexOf("class ");
            if (classIdx >= 0 && (classIdx == 0 || line.charAt(classIdx - 1) == ' ' ||
                    line.contains("public class"))) {
                int start = classIdx + 6;
                int end = line.indexOf(" ", start);
                if (end < 0) end = line.indexOf("{", start);
                if (end < 0) end = line.length();
                return line.substring(start, end).trim();
            }
        }
        return null;
    }

    /**
     * 读取进程输出
     */
    private String readProcessOutput(Process process) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) output.append("\n");
                output.append(line);
                first = false;
            }
        }
        return output.toString();
    }

    /**
     * 删除目录
     */
    private void deleteDirectory(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    /**
     * 保存提交状态
     */
    private void saveSubmissionStatus(CodeSubmission submission, String status,
                                       int passedCases, int totalCases,
                                       CodeExecutionResult result) {
        submission.setStatus(status);
        submission.setPassedCases(passedCases);
        submission.setTotalCases(totalCases);
        if (result != null) {
            submission.setExecuteTimeMs(result.getExecuteTimeMs());
            submission.setErrorMessage(result.getErrorMessage());
            submission.setJudgeResult(JSONUtil.toJsonStr(result.getTestCaseResults()));
        }
        codeSubmissionMapper.updateById(submission);
    }

    /**
     * 更新题目通过/提交统计数据
     */
    private void updateQuestionStats(Question question, Long questionId, boolean passed) {
        question.setSubmitNum((question.getSubmitNum() == null ? 0 : question.getSubmitNum()) + 1);
        if (passed) {
            question.setAcceptedNum((question.getAcceptedNum() == null ? 0 : question.getAcceptedNum()) + 1);
        }
        questionService.updateById(question);
    }
}
