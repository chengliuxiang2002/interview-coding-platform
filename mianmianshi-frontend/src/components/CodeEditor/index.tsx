"use client";
import React, { useState, useEffect } from "react";
import { Button, Card, Select, message, Spin, Tag, Tabs, Space, Typography } from "antd";
import { PlayCircleOutlined, CheckCircleOutlined, CloseCircleOutlined, ClockCircleOutlined } from "@ant-design/icons";
import MdViewer from "@/components/MdViewer";
import "./index.css";

const { Title, Text, Paragraph } = Typography;

interface TestCaseResult {
  index: number;
  passed: boolean;
  input: string;
  expectedOutput: string;
  actualOutput: string;
  executeTimeMs: number;
}

interface CodeExecutionResult {
  allPassed: boolean;
  passedCases: number;
  totalCases: number;
  status: string;
  executeTimeMs: number;
  testCaseResults: TestCaseResult[];
  errorMessage: string;
}

interface Props {
  question: API.QuestionVO;
}

/**
 * 代码编辑器与提交组件
 * 如需更好的编辑体验，可运行: npm install @monaco-editor/react
 * 然后将下方 textarea 替换为 Monaco Editor 组件
 */
const CodeEditor: React.FC<Props> = ({ question }) => {
  const [language, setLanguage] = useState<string>("java");
  const [code, setCode] = useState<string>("");
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState<CodeExecutionResult | null>(null);

  // 初始化代码模板
  useEffect(() => {
    const supportedLanguages = question.supportedLanguages || [];
    if (supportedLanguages.length > 0) {
      setLanguage(supportedLanguages[0]);
    }
    const codeTemplate = question.codeTemplate || {};
    if (codeTemplate && Object.keys(codeTemplate).length > 0) {
      setCode(codeTemplate[supportedLanguages[0]] || codeTemplate[Object.keys(codeTemplate)[0]] || "");
    }
  }, [question]);

  // 切换语言时加载对应模板
  const handleLanguageChange = (lang: string) => {
    setLanguage(lang);
    const codeTemplate = question.codeTemplate || {};
    setCode(codeTemplate[lang] || "");
    setResult(null);
  };

  // 提交代码
  const handleSubmit = async () => {
    if (!code.trim()) {
      message.warning("请先输入代码");
      return;
    }
    setSubmitting(true);
    setResult(null);
    try {
      const response = await fetch("/api/code/submit", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          questionId: question.id,
          language,
          code,
        }),
      });
      const data = await response.json();
      if (data.code === 0) {
        setResult(data.data);
      } else {
        message.error(data.message || "提交失败");
      }
    } catch (e: any) {
      message.error("提交失败，" + e.message);
    } finally {
      setSubmitting(false);
    }
  };

  // 状态标签
  const statusTag = (status: string) => {
    const statusMap: Record<string, { color: string; icon: React.ReactNode; text: string }> = {
      ACCEPTED: { color: "success", icon: <CheckCircleOutlined />, text: "通过" },
      WRONG_ANSWER: { color: "error", icon: <CloseCircleOutlined />, text: "答案错误" },
      COMPILE_ERROR: { color: "warning", icon: <CloseCircleOutlined />, text: "编译错误" },
      RUNTIME_ERROR: { color: "error", icon: <CloseCircleOutlined />, text: "运行错误" },
      TIME_LIMIT_EXCEEDED: { color: "warning", icon: <ClockCircleOutlined />, text: "执行超时" },
    };
    const info = statusMap[status] || { color: "default", icon: null, text: status };
    return <Tag color={info.color} icon={info.icon}>{info.text}</Tag>;
  };

  const supportedLanguages = question.supportedLanguages || [];

  return (
    <div className="code-editor-container">
      <Card
        title={
          <Space>
            <Text strong>代码编辑器</Text>
            <Select
              value={language}
              onChange={handleLanguageChange}
              style={{ width: 120 }}
              options={supportedLanguages.map((lang: string) => ({ value: lang, label: lang.toUpperCase() }))}
            />
          </Space>
        }
        extra={
          <Button
            type="primary"
            icon={<PlayCircleOutlined />}
            onClick={handleSubmit}
            loading={submitting}
            disabled={!code.trim()}
          >
            提交运行
          </Button>
        }
      >
        {/* 
          推荐使用 Monaco Editor: 
          1. 运行: npm install @monaco-editor/react
          2. 引入: import Editor from "@monaco-editor/react";
          3. 替换下方 textarea:
          <Editor
            height="400px"
            language={language}
            value={code}
            onChange={(value) => setCode(value || "")}
            theme="vs-dark"
            options={{ minimap: { enabled: false }, fontSize: 14 }}
          />
        */}
        <textarea
          className="code-textarea"
          value={code}
          onChange={(e) => setCode(e.target.value)}
          spellCheck={false}
          placeholder="在此输入您的代码..."
        />
      </Card>

      {/* 判题结果 */}
      {result && (
        <Card title="运行结果" style={{ marginTop: 16 }}>
          <div className="result-header">
            <Space>
              {statusTag(result.status)}
              <Text>
                通过 {result.passedCases}/{result.totalCases} 个测试用例
              </Text>
              <Text type="secondary">
                总耗时: {result.executeTimeMs}ms
              </Text>
            </Space>
          </div>

          {result.errorMessage && (
            <div className="error-message">
              <Text type="danger">{result.errorMessage}</Text>
            </div>
          )}

          {/* 测试用例详情 */}
          {result.testCaseResults && result.testCaseResults.length > 0 && (
            <div className="testcase-results">
              <Title level={5} style={{ marginTop: 16 }}>测试用例详情</Title>
              {result.testCaseResults.map((tc) => (
                <Card
                  key={tc.index}
                  size="small"
                  className={`testcase-card ${tc.passed ? "passed" : "failed"}`}
                  style={{ marginBottom: 8 }}
                  title={
                    <Space>
                      {tc.passed ? (
                        <CheckCircleOutlined style={{ color: "#52c41a" }} />
                      ) : (
                        <CloseCircleOutlined style={{ color: "#ff4d4f" }} />
                      )}
                      <Text>用例 #{tc.index}</Text>
                      <Tag color={tc.passed ? "success" : "error"}>
                        {tc.passed ? "通过" : "失败"}
                      </Tag>
                      <Text type="secondary">{tc.executeTimeMs}ms</Text>
                    </Space>
                  }
                >
                  {tc.input && (
                    <div>
                      <Text type="secondary">输入: </Text>
                      <Text code>{tc.input}</Text>
                    </div>
                  )}
                  <div>
                    <Text type="secondary">期望输出: </Text>
                    <Text code>{tc.expectedOutput}</Text>
                  </div>
                  <div>
                    <Text type="secondary">实际输出: </Text>
                    <Text code>{tc.actualOutput}</Text>
                  </div>
                </Card>
              ))}
            </div>
          )}
        </Card>
      )}
    </div>
  );
};

export default CodeEditor;
