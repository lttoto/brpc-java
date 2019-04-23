/*
 * Copyright (c) 2018 Baidu, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.baidu.brpc.server.handler;

import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.lang.reflect.InvocationTargetException;

import com.baidu.brpc.Controller;
import com.baidu.brpc.exceptions.RpcException;
import com.baidu.brpc.interceptor.DefaultInterceptorChain;
import com.baidu.brpc.interceptor.InterceptorChain;
import com.baidu.brpc.protocol.Protocol;
import com.baidu.brpc.protocol.Request;
import com.baidu.brpc.protocol.Response;
import com.baidu.brpc.protocol.RpcRequest;
import com.baidu.brpc.protocol.RpcResponse;
import com.baidu.brpc.protocol.http.BrpcHttpResponseEncoder;
import com.baidu.brpc.protocol.http.HttpRpcProtocol;
import com.baidu.brpc.server.RpcServer;
import com.baidu.brpc.server.ServerStatus;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Setter
@Getter
@AllArgsConstructor
public class ServerWorkTask implements Runnable {
    private RpcServer rpcServer;
    private Object packet;
    private Protocol protocol;
    private Request request;
    private Response response;
    private ChannelHandlerContext ctx;

    @Override
    public void run() {

        log.info("current thread is: {}", Thread.currentThread().getName());
        Controller controller = null;

        if (request != null) {
            request.setChannel(ctx.channel());
            if (request.getRpcMethodInfo().isIncludeController()
                    || request.getBinaryAttachment() != null
                    || request.getKvAttachment() != null) {
                controller = new Controller();
                if (request.getBinaryAttachment() != null) {
                    controller.setRequestBinaryAttachment(request.getBinaryAttachment());
                }
                if (request.getKvAttachment() != null) {
                    controller.setRequestKvAttachment(request.getKvAttachment());
                }
                controller.setRemoteAddress(ctx.channel().remoteAddress());
                request.setController(controller);
            }

            response.setLogId(request.getLogId());
            response.setCompressType(request.getCompressType());
            response.setException(request.getException());
            response.setRpcMethodInfo(request.getRpcMethodInfo());
        }

        if (response.getException() == null) {
            try {
                InterceptorChain interceptorChain = new DefaultInterceptorChain(rpcServer.getInterceptors());
                interceptorChain.intercept(request, response);
                if (controller != null && controller.getResponseBinaryAttachment() != null
                        && controller.getResponseBinaryAttachment().isReadable()) {
                    response.setBinaryAttachment(controller.getResponseBinaryAttachment());
                }
            } catch (InvocationTargetException ex) {
                Throwable targetException = ex.getTargetException();
                if (targetException == null) {
                    targetException = ex;
                }
                String errorMsg = String.format("invoke method failed, msg=%s", targetException.getMessage());
                log.warn(errorMsg, targetException);
                response.setException(targetException);
            } catch (Throwable ex) {
                String errorMsg = String.format("invoke method failed, msg=%s", ex.getMessage());
                log.warn(errorMsg, ex);
                response.setException(ex);
            }
        }

        try {
            ByteBuf byteBuf = protocol.encodeResponse(request, response);
            ChannelFuture channelFuture = ctx.channel().writeAndFlush(byteBuf);
            protocol.afterResponseSent(request, response, channelFuture);
        } catch (Exception ex) {
            log.warn("send response failed:", ex);
        }
    }
}
