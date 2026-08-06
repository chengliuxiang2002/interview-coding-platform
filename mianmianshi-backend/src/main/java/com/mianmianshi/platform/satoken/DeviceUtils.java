package com.mianmianshi.platform.satoken;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.Header;
import cn.hutool.http.useragent.UserAgent;
import cn.hutool.http.useragent.UserAgentUtil;
import com.mianmianshi.platform.common.ErrorCode;
import com.mianmianshi.platform.exception.ThrowUtils;

import javax.servlet.http.HttpServletRequest;

/**
 * 设备工具�?
 */
public class DeviceUtils {

    /**
     * 根据请求获取设备信息
     * @param request
     * @return
     */
    public static String getRequestDevice(HttpServletRequest request) {
        String userAgentStr = request.getHeader(Header.USER_AGENT.toString());
        // 使用 Hutool 解析 UserAgent
        UserAgent userAgent = UserAgentUtil.parse(userAgentStr);
        ThrowUtils.throwIf(userAgent == null, ErrorCode.OPERATION_ERROR, "非法请求");
        // 默认值是 PC
        String device = "pc";
        // 是否为小程序
        if (isMiniProgram(userAgentStr)) {
            device = "miniProgram";
        } else if (isPad(userAgentStr)) {
            // 是否�?Pad
            device = "pad";
        } else if (userAgent.isMobile()) {
            // 是否为手�?
            device = "mobile";
        }
        return device;
    }

    /**
     * 判断是否是小程序
     * 一般通过 User-Agent 字符串中�?"MicroMessenger" 来判断是否是微信小程�?
     **/
    private static boolean isMiniProgram(String userAgentStr) {
        // 判断 User-Agent 是否包含 "MicroMessenger" 表示是微信环�?
        return StrUtil.containsIgnoreCase(userAgentStr, "MicroMessenger")
                && StrUtil.containsIgnoreCase(userAgentStr, "MiniProgram");
    }

    /**
     * 判断是否为平板设�?
     * 支持 iOS（如 iPad）和 Android 平板的检�?
     **/
    private static boolean isPad(String userAgentStr) {
        // 检�?iPad �?User-Agent 标志
        boolean isIpad = StrUtil.containsIgnoreCase(userAgentStr, "iPad");

        // 检�?Android 平板（包�?"Android" 且不包含 "Mobile"�?
        boolean isAndroidTablet = StrUtil.containsIgnoreCase(userAgentStr, "Android")
                && !StrUtil.containsIgnoreCase(userAgentStr, "Mobile");

        // 如果�?iPad �?Android 平板，则返回 true
        return isIpad || isAndroidTablet;
    }
}
