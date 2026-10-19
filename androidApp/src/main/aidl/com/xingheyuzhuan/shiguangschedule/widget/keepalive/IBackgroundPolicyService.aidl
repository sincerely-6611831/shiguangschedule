package com.xingheyuzhuan.shiguangschedule.widget.keepalive;

// 仅支持本应用的三项后台策略，不提供任意命令执行接口。
interface IBackgroundPolicyService {
    void destroy() = 16777114;
    String readPolicy(int userId) = 1;
    void applyPolicy(int userId, String policy) = 2;
}
