import React from "react";
import "./index.css";

/**
 * 全局底部栏组件
 */
export default function GlobalFooter() {
  const currentYear = new Date().getFullYear();

  return (
    <div className="global-footer">
      <div>© {currentYear} 面面是刷题平台</div>
      <div>
        <a href="https://github.com" target="_blank">
          面试刷题，助你上岸
        </a>
      </div>
    </div>
  );
}
