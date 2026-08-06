"use client";
import { useEffect, useState } from "react";
import { message, Spin, Tag, Space, Typography } from "antd";
import { getQuestionVoByIdUsingGet } from "@/api/questionController";
import QuestionCard from "@/components/QuestionCard";
import CodeEditor from "@/components/CodeEditor";
import "./index.css";

const { Title } = Typography;

/**
 * 题目详情页 - 包含题目描述和在线代码编辑器
 */
export default function QuestionPage({ params }: { params: { questionId: string } }) {
  const { questionId } = params;
  const [question, setQuestion] = useState<API.QuestionVO | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchQuestion = async () => {
      setLoading(true);
      try {
        const res = await getQuestionVoByIdUsingGet({ id: Number(questionId) });
        if (res.data) {
          setQuestion(res.data);
        }
      } catch (e: any) {
        message.error("获取题目详情失败，" + e.message);
      } finally {
        setLoading(false);
      }
    };
    fetchQuestion();
  }, [questionId]);

  if (loading) {
    return (
      <div style={{ textAlign: "center", padding: 100 }}>
        <Spin size="large" tip="加载中..." />
      </div>
    );
  }

  if (!question) {
    return <div>获取题目详情失败，请刷新重试</div>;
  }

  // 难度标签
  const difficultyMap: Record<string, { color: string; text: string }> = {
    easy: { color: "success", text: "简单" },
    medium: { color: "warning", text: "中等" },
    hard: { color: "error", text: "困难" },
  };
  const diffInfo = difficultyMap[question.difficulty || ""] || { color: "default", text: question.difficulty };

  return (
    <div id="questionPage">
      <div className="question-header">
        <Space align="center" size="middle">
          <Title level={3} style={{ margin: 0 }}>{question.title}</Title>
          {question.difficulty && (
            <Tag color={diffInfo.color}>{diffInfo.text}</Tag>
          )}
          {question.tagList && question.tagList.length > 0 && (
            <Space>
              {question.tagList.map((tag: string) => (
                <Tag key={tag}>{tag}</Tag>
              ))}
            </Space>
          )}
        </Space>
        {question.submitNum !== undefined && (
          <div style={{ marginTop: 8 }}>
            <Space>
              <span>提交: {question.submitNum}</span>
              <span>通过: {question.acceptedNum || 0}</span>
              {question.submitNum > 0 && (
                <span>
                  通过率: {((question.acceptedNum || 0) / question.submitNum * 100).toFixed(1)}%
                </span>
              )}
            </Space>
          </div>
        )}
      </div>

      <QuestionCard question={question} />

      {/* 在线代码编辑器 */}
      {question.supportedLanguages && question.supportedLanguages.length > 0 && (
        <div style={{ marginTop: 24 }}>
          <CodeEditor question={question} />
        </div>
      )}
    </div>
  );
}
