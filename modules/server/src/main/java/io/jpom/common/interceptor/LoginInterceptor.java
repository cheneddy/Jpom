/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 Code Technology Studio
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.jpom.common.interceptor;

import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.servlet.ServletUtil;
import cn.hutool.jwt.JWT;
import cn.jiangzeyin.common.JsonMessage;
import cn.jiangzeyin.common.interceptor.InterceptorPattens;
import cn.jiangzeyin.common.spring.SpringUtil;
import io.jpom.common.BaseServerController;
import io.jpom.common.ServerOpenApi;
import io.jpom.model.user.UserModel;
import io.jpom.service.user.UserService;
import io.jpom.system.ServerConfigBean;
import io.jpom.system.ServerExtConfigBean;
import io.jpom.system.db.DbConfig;
import io.jpom.util.JwtUtil;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 登录拦截器
 *
 * @author jiangzeyin
 * @since 2017/2/4.
 */
@InterceptorPattens(sort = -1, exclude = ServerOpenApi.API + "**")
public class LoginInterceptor extends BaseJpomInterceptor {
    /**
     * session
     */
    public static final String SESSION_NAME = "user";

    private static final Map<Integer, String> MSG_CACHE = new HashMap<>(3);

    static {
        MSG_CACHE.put(ServerConfigBean.AUTHORIZE_TIME_OUT_CODE, ServerConfigBean.LOGIN_TIP);
        MSG_CACHE.put(ServerConfigBean.RENEWAL_AUTHORIZE_CODE, ServerConfigBean.LOGIN_TIP);
        MSG_CACHE.put(ServerConfigBean.ACCOUNT_LOCKED, ServerConfigBean.ACCOUNT_LOCKED_TIP);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, HandlerMethod handlerMethod) throws Exception {
        HttpSession session = getSession();
        boolean init = DbConfig.getInstance().isInit();
        if (!init) {
            ServletUtil.write(response, JsonMessage.getString(100, "数据库还没有初始化成功,请耐心等待"), MediaType.APPLICATION_JSON_VALUE);
            return false;
        }
        //
        NotLogin notLogin = handlerMethod.getMethodAnnotation(NotLogin.class);
        if (notLogin == null) {
            notLogin = handlerMethod.getBeanType().getAnnotation(NotLogin.class);
        }
        if (notLogin == null) {
            // 这里需要判断请求头里是否有 Authorization 属性
            String authorization = request.getHeader(ServerOpenApi.HTTP_HEAD_AUTHORIZATION);
            if (StrUtil.isNotEmpty(authorization)) {
                // jwt token 检测机制
                int code = this.checkHeaderUser(request, session);
                if (code > 0) {
                    this.responseLogin(request, session, response, code);
                    return false;
                }
            }
            // 老版本登录拦截
            int code = this.tryGetHeaderUser(request, session);
            if (code > 0) {
                this.responseLogin(request, session, response, ServerConfigBean.AUTHORIZE_TIME_OUT_CODE);
                return false;
            }
        }
        reload();
        //
        return true;
    }

    /**
     * 尝试获取 header 中的信息
     *
     * @param session ses
     * @param request req
     * @return true 获取成功
     */
    private int checkHeaderUser(HttpServletRequest request, HttpSession session) {
        String token = request.getHeader(ServerOpenApi.HTTP_HEAD_AUTHORIZATION);
        if (StrUtil.isEmpty(token)) {
            return ServerConfigBean.AUTHORIZE_TIME_OUT_CODE;
        }
        JWT jwt = JwtUtil.readBody(token);
        if (JwtUtil.expired(jwt, 0)) {
            int renewal = ServerExtConfigBean.getInstance().getAuthorizeRenewal();
            if (jwt == null || renewal <= 0 || JwtUtil.expired(jwt, TimeUnit.MINUTES.toSeconds(renewal))) {
                return ServerConfigBean.AUTHORIZE_TIME_OUT_CODE;
            }
            return ServerConfigBean.RENEWAL_AUTHORIZE_CODE;
        }
        UserModel user = (UserModel) session.getAttribute(SESSION_NAME);
        UserService userService = SpringUtil.getBean(UserService.class);
        String id = JwtUtil.getId(jwt);
        UserModel newUser = userService.checkUser(id);
        if (newUser == null) {
            return ServerConfigBean.AUTHORIZE_TIME_OUT_CODE;
        }
        if (null != user) {
            String tokenUserId = JwtUtil.readUserId(jwt);
            boolean b = user.getId().equals(tokenUserId);
            if (!b) {
                return ServerConfigBean.AUTHORIZE_TIME_OUT_CODE;
            }
        }
        if (newUser.getStatus() != null && newUser.getStatus() == 0) {
            // 账号禁用
            return ServerConfigBean.ACCOUNT_LOCKED;
        }
        session.setAttribute(LoginInterceptor.SESSION_NAME, newUser);
        return 0;
    }


    /**
     * 尝试获取 header 中的信息
     *
     * @param session ses
     * @param request req
     * @return 状态码
     */
    private int tryGetHeaderUser(HttpServletRequest request, HttpSession session) {
        String header = request.getHeader(ServerOpenApi.USER_TOKEN_HEAD);
        if (StrUtil.isEmpty(header)) {
            // 兼容就版本 登录状态
            UserModel user = (UserModel) session.getAttribute(SESSION_NAME);
            return user != null ? 0 : ServerConfigBean.AUTHORIZE_TIME_OUT_CODE;
        }
        UserService userService = SpringUtil.getBean(UserService.class);
        UserModel userModel = userService.checkUser(header);
        if (userModel == null) {
            return ServerConfigBean.AUTHORIZE_TIME_OUT_CODE;
        }
        if (userModel.getStatus() != null && userModel.getStatus() == 0) {
            // 账号禁用
            return ServerConfigBean.ACCOUNT_LOCKED;
        }
        session.setAttribute(LoginInterceptor.SESSION_NAME, userModel);
        return 0;
    }

    /**
     * 提示登录
     *
     * @param request  req
     * @param session  回话
     * @param response res
     * @throws IOException 异常
     */
    private void responseLogin(HttpServletRequest request, HttpSession session, HttpServletResponse response, int code) throws IOException {
        session.removeAttribute(LoginInterceptor.SESSION_NAME);
        String msg = MSG_CACHE.getOrDefault(code, ServerConfigBean.LOGIN_TIP);
        ServletUtil.write(response, JsonMessage.getString(code, msg), MediaType.APPLICATION_JSON_VALUE);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        super.afterCompletion(request, response, handler, ex);
        BaseServerController.removeAll();
    }
}
