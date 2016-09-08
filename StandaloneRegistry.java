/*
 * Copyright 1999-2011 Alibaba Group.
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.registry.standalone;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.common.utils.UrlUtils;
import com.alibaba.dubbo.registry.NotifyListener;
import com.alibaba.dubbo.registry.support.FailbackRegistry;

/**
 * MulticastRegistry
 * 
 * @author william.liangf
 */
public class StandaloneRegistry extends FailbackRegistry {

    // 日志输出
	private static final Logger logger = LoggerFactory.getLogger(StandaloneRegistry.class);

    private static final int DEFAULT_MULTICAST_PORT = 4234;

    private final InetAddress ipAddress;

    private final int port;

    private final ConcurrentMap<URL, Set<URL>> received = new ConcurrentHashMap<URL, Set<URL>>();


    
    private volatile boolean admin = false;
    
    /**默认超时时间，10毫秒。
     * 
     */
    public static final int   DEFAULT_TIMEOUT  = 10;
    
    /**
     * 角色
     */
    private static final String ROLE_PARAM = "role";
    
    /**客户端socket
     * 
     */
    private  Socket consumerSocket;
    
    /**
     * 服务端的服务socket
     */
    private  ServerSocket providerServerSocket;
    
    /**
     * 服务端socket
     */
    private  Socket provideSocket;
    
    /**
     * 是否提供者。ture表示提供者，反之表示消费者。
     */
    private boolean isProvider = false;

    public StandaloneRegistry(URL url) {
        super(url);
        if (url.isAnyHost()) {
    		throw new IllegalStateException("registry address == null");
    	}
        if (! isIpAddress(url.getHost())) {
            throw new IllegalArgumentException("Invalid ip address " + url.getHost());
        }
        
        try {
        	
        	ipAddress = InetAddress.getByName(url.getHost());
        	port = url.getPort() <= 0 ? DEFAULT_MULTICAST_PORT : url.getPort();
        	
        	String role = url.getParameter(ROLE_PARAM);
        	if ("server".equals(role)) {
        		providerServerSocket = new ServerSocket(port, 50 , ipAddress);
        		isProvider = true;
        	} else {
        		consumerSocket = new Socket(ipAddress, port);
        	}

        	Thread thread = new Thread(new Runnable() {
                public void run() {
                	if (isProvider) {
                		 while (! providerServerSocket.isClosed()) {
                			 try {
                				 provideSocket = providerServerSocket.accept();
	                			 InputStream input = provideSocket.getInputStream();
	                			 
	                			 BufferedReader bufInput = new BufferedReader(new InputStreamReader(input));
	                			 String readLine;
	                			while ((readLine = bufInput.readLine()) != null) {
	                				System.out.println("server接收的数据：" + readLine);
	                				logger.info(readLine);
	                				receive(readLine, (InetSocketAddress)provideSocket.getRemoteSocketAddress(), provideSocket.getOutputStream());
	                			}
                			 } catch (Throwable e) {
                				 	logger.error(e.getMessage(), e);
                				 	e.printStackTrace();
                                 if (provideSocket !=  null && !provideSocket.isClosed()) {
                                	 try {
										provideSocket.close();
									} catch (IOException e1) {
										e1.printStackTrace();
									}
                                 }
                             }
                		 }
                	} else {
	               		 while (!consumerSocket.isClosed()) {
	            			 try {
	                			 InputStream input = consumerSocket.getInputStream();
	                			 
	                			 BufferedReader bufInput = new BufferedReader(new InputStreamReader(input));
	                			 String readLine;
	                			while ((readLine = bufInput.readLine()) != null) {
	                				System.out.println("consumer接收的数据：" + readLine);
	                				receive(readLine, (InetSocketAddress)consumerSocket.getRemoteSocketAddress(), consumerSocket.getOutputStream());
	                			}
	            			 } catch (Throwable e) {
	            				 logger.error(e.getMessage(), e);
	            				 e.printStackTrace();
	                             if (consumerSocket !=  null && !consumerSocket.isClosed()) {
                                	 try {
                                		 consumerSocket.close();
									} catch (IOException e1) {
										e1.printStackTrace();
									}
                                 }
	                         }
	            		 }
	                	}
                }	
            }, "StandaloneRegistry-Receiver-Launcher");
            thread.setDaemon(false);
            thread.setPriority(Thread.MAX_PRIORITY);
            thread.start();
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
    
    /** 是否 ip地址
     * @param ip
     * @return
     */
    private static boolean isIpAddress(String ip) {
        return ip.split("[.]").length == 4;
    }
    
    /**
     * 清除过期者。暂时不提供此使用功能。
     */
    @SuppressWarnings("unused")
	private void clean() {
        if (admin) {
            for (Set<URL> providers : new HashSet<Set<URL>>(received.values())) {
                for (URL url : new HashSet<URL>(providers)) {
                    if (isExpired(url)) {
                        if (logger.isWarnEnabled()) {
                            logger.warn("Clean expired provider " + url);
                        }
                        doUnregister(url);
                    }
                }
            }
        }
    }
    
    private boolean isExpired(URL url) {
        if (! url.getParameter(Constants.DYNAMIC_KEY, true)
        		|| url.getPort() <= 0
        		|| Constants.CONSUMER_PROTOCOL.equals(url.getProtocol())
                || Constants.ROUTE_PROTOCOL.equals(url.getProtocol())
                || Constants.OVERRIDE_PROTOCOL.equals(url.getProtocol())) {
            return false;
        }
        Socket socket = null;
        try {
            socket = new Socket(url.getHost(), url.getPort());
        } catch (Throwable e) {
            try {
                Thread.sleep(100);
            } catch (Throwable e2) {
            }
            Socket socket2 = null;
            try {
                socket2 = new Socket(url.getHost(), url.getPort());
            } catch (Throwable e2) {
                return true;
            } finally {
                if (socket2 != null) {
                    try {
                        socket2.close();
                    } catch (Throwable e2) {
                    }
                }
            }
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable e) {
                }
            }
        }
        return false;
    }

    /**接收数据
     * @param msg 消息
     * @param remoteAddress 远程地址
     * @param output 本地服务输出流
     * @throws IOException 
     */
    private void receive(String msg, InetSocketAddress remoteAddress, OutputStream output) throws IOException {
        if (logger.isInfoEnabled()) {
            logger.info("Receive multicast message: " + msg + " from " + remoteAddress);
        }
        if (msg.startsWith(Constants.REGISTER)) {
            URL url = URL.valueOf(msg.substring(Constants.REGISTER.length()).trim());
            registered(url);
        } else if (msg.startsWith(Constants.UNREGISTER)) {
            URL url = URL.valueOf(msg.substring(Constants.UNREGISTER.length()).trim());
            unregistered(url);
        } else if (msg.startsWith(Constants.SUBSCRIBE)) {
            URL url = URL.valueOf(msg.substring(Constants.SUBSCRIBE.length()).trim());
            Set<URL> urls = getRegistered();
            if (urls != null && urls.size() > 0) {
            	 BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(output));
                for (URL u : urls) {
                    if (UrlUtils.isMatch(url, u)) {
                    	String sendMsg = Constants.REGISTER + " " + u.toFullString();
                    	bw.write(sendMsg);
                    	bw.newLine();
                    }
                }
                bw.flush();
            }
        }/* else if (msg.startsWith(UNSUBSCRIBE)) {
        }*/
    }
    
    private void notice(String msg, OutputStream output) {
        if (logger.isInfoEnabled()) {
            logger.info("Send broadcast message: " + msg + " to " + ipAddress + ":" + port);
        }
        if (output == null) {
        	return;
        }
        try {
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(output));
            bw.write(msg);
            bw.newLine();
            bw.flush();
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
    
    protected void doRegister(URL url) {
    	OutputStream outputStream = null;
        try {
        	if (canUseProviderSocket()) {
	        	outputStream = provideSocket.getOutputStream();
	        } else if (canUseConsumerSocket()) {
	        	outputStream = consumerSocket.getOutputStream();
	        }
        } catch (IOException e) {
			e.printStackTrace();
		}
        
        notice(Constants.REGISTER + " " + url.toFullString(), outputStream);
    }

    protected void doUnregister(URL url) {
    	OutputStream outputStream = null;
        try {
        	if (canUseProviderSocket()) {
	        	outputStream = provideSocket.getOutputStream();
	        } else if (canUseConsumerSocket()) {
	        	outputStream = consumerSocket.getOutputStream();
	        }
        } catch (IOException e) {
			e.printStackTrace();
		}
        
        notice(Constants.UNREGISTER + " " + url.toFullString(), outputStream);
    }
    
    /**是否可以使用提供者socket
     * @return
     */
    private boolean canUseProviderSocket() {
    	return isProvider && providerServerSocket != null && 
    			!providerServerSocket.isClosed() && provideSocket != null && !provideSocket.isClosed();
    }
    
    /**是否可以使用消费者socket
     * @return
     */
    private boolean canUseConsumerSocket() {
    	return !isProvider && consumerSocket != null && !consumerSocket.isClosed();
    }

    protected void doSubscribe(URL url, NotifyListener listener) {
        if (Constants.ANY_VALUE.equals(url.getServiceInterface())) {
            admin = true;
        }
        OutputStream outputStream = null;
        try {
        	if (canUseProviderSocket()) {
	        	outputStream = provideSocket.getOutputStream();
	        } else if (canUseConsumerSocket()) {
	        	outputStream = consumerSocket.getOutputStream();
	        }
        } catch (IOException e) {
			e.printStackTrace();
		}
    		
		notice(Constants.SUBSCRIBE + " " + url.toFullString(), outputStream);
		
		if (canUseConsumerSocket()) {
			synchronized (listener) {
				try {
					listener.wait(DEFAULT_TIMEOUT);
				} catch (InterruptedException e) {
				}
			}
		}
    }

    protected void doUnsubscribe(URL url, NotifyListener listener) {
        if (! Constants.ANY_VALUE.equals(url.getServiceInterface())
                && url.getParameter(Constants.REGISTER_KEY, true)) {
            unregister(url);
        }
        
        OutputStream outputStream = null;
        try {
        	if (canUseProviderSocket()) {
	        	outputStream = provideSocket.getOutputStream();
	        } else if (canUseConsumerSocket()) {
	        	outputStream = consumerSocket.getOutputStream();
	        }
        } catch (IOException e) {
			e.printStackTrace();
		}
        
        notice(Constants.UNSUBSCRIBE + " " + url.toFullString(), outputStream);
    }

    public boolean isAvailable() {
        try {
            return (consumerSocket != null && !consumerSocket.isClosed())  ||
            			(providerServerSocket != null && !providerServerSocket.isClosed());
        } catch (Throwable t) {
            return false;
        }
    }

    public void destroy() {
        super.destroy();
        try {
        	if (!isProvider && consumerSocket != null) {
        		consumerSocket.close();
        	}
        } catch (Throwable t) {
        	logger.warn(t.getMessage(), t);
        	t.printStackTrace();
        }
        
        try {
        	if (isProvider && providerServerSocket != null) {
        		providerServerSocket.close();
        	}
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
            t.printStackTrace();
        }
    }

    protected void registered(URL url) {
        for (Map.Entry<URL, Set<NotifyListener>> entry : getSubscribed().entrySet()) {
            URL key = entry.getKey();
            if (UrlUtils.isMatch(key, url)) {
                Set<URL> urls = received.get(key);
                if (urls == null) {
                    received.putIfAbsent(key, new ConcurrentHashSet<URL>());
                    urls = received.get(key);
                }
                urls.add(url);
                List<URL> list = toList(urls);
                for (NotifyListener listener : entry.getValue()) {
                    notify(key, listener, list);
                    synchronized (listener) {
                        listener.notify();
                    }
                }
            }
        }
    }

    protected void unregistered(URL url) {
        for (Map.Entry<URL, Set<NotifyListener>> entry : getSubscribed().entrySet()) {
            URL key = entry.getKey();
            if (UrlUtils.isMatch(key, url)) {
                Set<URL> urls = received.get(key);
                if (urls != null) {
                    urls.remove(url);
                }
                List<URL> list = toList(urls);
                for (NotifyListener listener : entry.getValue()) {
                    notify(key, listener, list);
                }
            }
        }
    }

    protected void subscribed(URL url, NotifyListener listener) {
        List<URL> urls = lookup(url);
        notify(url, listener, urls);
    }

    private List<URL> toList(Set<URL> urls) {
        List<URL> list = new ArrayList<URL>();
        if (urls != null && urls.size() > 0) {
            for (URL url : urls) {
                list.add(url);
            }
        }
        return list;
    }

    public void register(URL url) {
        super.register(url);
        registered(url);
    }

    public void unregister(URL url) {
        super.unregister(url);
        unregistered(url);
    }

    public void subscribe(URL url, NotifyListener listener) {
        super.subscribe(url, listener);
        subscribed(url, listener);
    }

    public void unsubscribe(URL url, NotifyListener listener) {
        super.unsubscribe(url, listener);
        received.remove(url);
    }

    public List<URL> lookup(URL url) {
        List<URL> urls= new ArrayList<URL>();
        Map<String, List<URL>> notifiedUrls = getNotified().get(url);
        if (notifiedUrls != null && notifiedUrls.size() > 0) {
            for (List<URL> values : notifiedUrls.values()) {
                urls.addAll(values);
            }
        }
        if (urls == null || urls.size() == 0) {
            List<URL> cacheUrls = getCacheUrls(url);
            if (cacheUrls != null && cacheUrls.size() > 0) {
                urls.addAll(cacheUrls);
            }
        }
        if (urls == null || urls.size() == 0) {
            for (URL u: getRegistered()) {
                if (UrlUtils.isMatch(url, u)) {
                    urls.add(u);
                }
            }
        }
        if (Constants.ANY_VALUE.equals(url.getServiceInterface())) {
            for (URL u: getSubscribed().keySet()) {
                if (UrlUtils.isMatch(url, u)) {
                    urls.add(u);
                }
            }
        }
        return urls;
    }


    public Map<URL, Set<URL>> getReceived() {
        return received;
    }

}