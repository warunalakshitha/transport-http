/*
 *  Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */

package org.wso2.transport.http.netty.listener;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.transport.http.netty.common.Constants;
import org.wso2.transport.http.netty.contract.ServerConnectorFuture;
import org.wso2.transport.http.netty.contractimpl.websocket.message.DefaultWebSocketInitMessage;
import org.wso2.transport.http.netty.message.DefaultListener;
import org.wso2.transport.http.netty.message.HttpCarbonRequest;
import org.wso2.transport.http.netty.message.PooledDataStreamerFactory;

import java.net.InetSocketAddress;

import static org.wso2.transport.http.netty.common.Constants.HTTP_OBJECT_AGGREGATOR;

/**
 * WebSocket handshake handler for carbon transports.
 */
public class WebSocketServerHandshakeHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(WebSocketServerHandshakeHandler.class);

    private static int maxContentLength = 8192;
    private final ServerConnectorFuture serverConnectorFuture;
    private final String interfaceId;

    public WebSocketServerHandshakeHandler(ServerConnectorFuture serverConnectorFuture, String interfaceId) {
        this.serverConnectorFuture = serverConnectorFuture;
        this.interfaceId = interfaceId;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {

        if (msg instanceof HttpRequest) {
            HttpRequest httpRequest = (HttpRequest) msg;
            HttpMethod requestMethod = httpRequest.method();

            if (containsUpgradeHeaders(httpRequest)) {
                if (HttpMethod.GET == requestMethod) {
                    if (log.isDebugEnabled()) {
                        log.debug("Upgrading the connection from Http to WebSocket for " +
                                          "channel : " + ctx.channel());
                    }
                    ChannelPipeline pipeline = ctx.pipeline();
                    ChannelHandlerContext decoderCtx = pipeline.context(HttpRequestDecoder.class);
                    pipeline.addAfter(decoderCtx.name(), HTTP_OBJECT_AGGREGATOR,
                            new HttpObjectAggregator(maxContentLength));
                    pipeline.addAfter(HTTP_OBJECT_AGGREGATOR, "handshake",
                            new SimpleChannelInboundHandler<FullHttpRequest>() {
                        @Override
                        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
                            // Remove ourselves and do the actual handshake
                            ctx.pipeline().remove(this);
                            handleWebSocketHandshake(msg, ctx);
                        }

                        @Override
                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                            // Remove ourselves and fail the handshake
                            ctx.pipeline().remove(this);
                            ctx.fireExceptionCaught(cause);
                        }

                        @Override
                        public void channelInactive(ChannelHandlerContext ctx) {
                            ctx.fireChannelInactive();
                        }
                    });
                    decoderCtx.fireChannelRead(msg);
                } else {
                    // According to spec since client must send a request with "GET" method, if method is not "GET"
                    // sends a bad request (status 400) in response.
                    DefaultHttpResponse badRequestResponse = new DefaultHttpResponse(httpRequest.protocolVersion(),
                                                                                     HttpResponseStatus.BAD_REQUEST);
                    ctx.writeAndFlush(badRequestResponse).addListener(future -> ctx.close());
                }
                return;
            }
        }
        ctx.fireChannelRead(msg);
    }

    /**
     * Check the basic headers needed for WebSocket upgrade are contained in the request.
     *
     * @param httpRequest {@link HttpRequest} which is checked for WebSocket upgrade.
     * @return true if basic headers needed for WebSocket upgrade are contained in the request.
     */
    private boolean containsUpgradeHeaders(HttpRequest httpRequest) {
        HttpHeaders headers = httpRequest.headers();
        return headers.containsValue(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE, true) &&
                headers.containsValue(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET, true);
    }

    /**
     * Handle the WebSocket handshake.
     *
     * @param fullHttpRequest {@link HttpRequest} of the request.
     */
    private void handleWebSocketHandshake(FullHttpRequest fullHttpRequest, ChannelHandlerContext ctx) throws Exception {
        DefaultWebSocketInitMessage initMessage =
                new DefaultWebSocketInitMessage(ctx, serverConnectorFuture, fullHttpRequest);

        // Setting common properties for init message
        initMessage.setIsServerMessage(true);
        initMessage.setTarget(fullHttpRequest.uri());
        initMessage.setListenerInterface(interfaceId);
        initMessage.setIsConnectionSecured(ctx.channel().pipeline().get(Constants.SSL_HANDLER) != null);

        initMessage.setHttpCarbonRequest(setupHttpCarbonRequest(fullHttpRequest, ctx));

        ctx.channel().config().setAutoRead(false);
        serverConnectorFuture.notifyWSListener(initMessage);
    }

    private HttpCarbonRequest setupHttpCarbonRequest(HttpRequest httpRequest, ChannelHandlerContext ctx) {

        HttpCarbonRequest sourceReqCmsg = new HttpCarbonRequest(httpRequest, new DefaultListener(ctx));
        sourceReqCmsg.setProperty(Constants.POOLED_BYTE_BUFFER_FACTORY, new PooledDataStreamerFactory(ctx.alloc()));

        sourceReqCmsg.setProperty(Constants.CHNL_HNDLR_CTX, ctx);
        sourceReqCmsg.setProperty(Constants.SRC_HANDLER, this);
        HttpVersion protocolVersion = httpRequest.protocolVersion();
        sourceReqCmsg.setProperty(Constants.HTTP_VERSION,
                                  protocolVersion.majorVersion() + "." + protocolVersion.minorVersion());
        sourceReqCmsg.setProperty(Constants.HTTP_METHOD, httpRequest.method().name());
        InetSocketAddress localAddress = null;

        //This check was added because in case of netty embedded channel, this could be of type 'EmbeddedSocketAddress'.
        if (ctx.channel().localAddress() instanceof InetSocketAddress) {
            localAddress = (InetSocketAddress) ctx.channel().localAddress();
        }
        sourceReqCmsg.setProperty(Constants.LISTENER_PORT, localAddress != null ? localAddress.getPort() : null);
        sourceReqCmsg.setProperty(Constants.LISTENER_INTERFACE_ID, interfaceId);
        sourceReqCmsg.setProperty(Constants.PROTOCOL, Constants.HTTP_SCHEME);

        boolean isSecuredConnection = false;
        if (ctx.channel().pipeline().get(Constants.SSL_HANDLER) != null) {
            isSecuredConnection = true;
        }
        sourceReqCmsg.setProperty(Constants.IS_SECURED_CONNECTION, isSecuredConnection);

        sourceReqCmsg.setProperty(Constants.LOCAL_ADDRESS, ctx.channel().localAddress());
        sourceReqCmsg.setProperty(Constants.REMOTE_ADDRESS, ctx.channel().remoteAddress());
        sourceReqCmsg.setProperty(Constants.REQUEST_URL, httpRequest.uri());
        sourceReqCmsg.setProperty(Constants.TO, httpRequest.uri());

        return sourceReqCmsg;
    }
}
