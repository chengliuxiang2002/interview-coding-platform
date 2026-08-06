"use client";
import { Card, List, Tag, Space } from "antd";
import { CheckCircleOutlined, CloseCircleOutlined } from "@ant-design/icons";
import TagList from "@/components/TagList";
import Link from "next/link";
import "./index.css";

interface Props {
  questionBankId?: number;
  questionList: API.QuestionVO[];
  cardTitle?: string;
}

// 难度映射
const difficultyMap: Record<string, { color: string; text: string }> = {
  easy: { color: "success", text: "简单" },
  medium: { color: "warning", text: "中等" },
  hard: { color: "error", text: "困难" },
};

/**
 * 题目列表组件
 */
const QuestionList = (props: Props) => {
  const { questionList = [], cardTitle, questionBankId } = props;

  return (
    <Card className="question-list" title={cardTitle}>
      <List
        dataSource={questionList}
        renderItem={(item) => (
          <List.Item
            extra={
              <Space>
                {item.difficulty && (
                  <Tag color={difficultyMap[item.difficulty]?.color}>
                    {difficultyMap[item.difficulty]?.text || item.difficulty}
                  </Tag>
                )}
                <TagList tagList={item.tagList} />
              </Space>
            }
          >
            <List.Item.Meta
              title={
                <Link
                  href={
                    questionBankId
                      ? `/bank/${questionBankId}/question/${item.id}`
                      : `/question/${item.id}`
                  }
                >
                  {item.title}
                </Link>
              }
              description={
                item.submitNum !== undefined ? (
                  <span style={{ fontSize: 12, color: "#999" }}>
                    通过率: {item.submitNum > 0
                      ? ((item.acceptedNum || 0) / item.submitNum * 100).toFixed(1)
                      : 0}%
                  </span>
                ) : null
              }
            />
          </List.Item>
        )}
      />
    </Card>
  );
};

export default QuestionList;
