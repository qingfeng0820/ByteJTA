/**
 * Copyright 2014-2017 yangming.liu<bytefox@126.com>.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, see <http://www.gnu.org/licenses/>.
 */
package org.bytesoft.bytejta.supports.springcloud.feign;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bytesoft.bytejta.TransactionImpl;
import org.bytesoft.bytejta.supports.rpc.TransactionRequestImpl;
import org.bytesoft.bytejta.supports.springcloud.SpringCloudBeanRegistry;
import org.bytesoft.bytejta.supports.springcloud.loadbalancer.TransactionLoadBalancerInterceptor;
import org.bytesoft.bytejta.supports.springcloud.rpc.TransactionResponseImpl;
import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.transaction.TransactionBeanFactory;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.TransactionManager;
import org.bytesoft.transaction.archive.XAResourceArchive;
import org.bytesoft.transaction.supports.rpc.TransactionInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.loadbalancer.Server;
import com.netflix.loadbalancer.Server.MetaInfo;

import feign.InvocationHandlerFactory.MethodHandler;
import feign.Target;

public class TransactionFeignHandler implements InvocationHandler {
	static final Logger logger = LoggerFactory.getLogger(TransactionFeignHandler.class);

	private Target<?> target;
	private Map<Method, MethodHandler> handlers;

	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		if (Object.class.equals(method.getDeclaringClass())) {
			return method.invoke(this, args);
		} else {
			final SpringCloudBeanRegistry beanRegistry = SpringCloudBeanRegistry.getInstance();
			TransactionBeanFactory beanFactory = beanRegistry.getBeanFactory();
			TransactionManager transactionManager = beanFactory.getTransactionManager();
			final TransactionInterceptor transactionInterceptor = beanFactory.getTransactionInterceptor();

			TransactionImpl transaction = //
					(TransactionImpl) transactionManager.getTransactionQuietly();
			if (transaction == null) {
				return this.handlers.get(method).invoke(args);
			}

			final TransactionContext transactionContext = transaction.getTransactionContext();

			final TransactionRequestImpl request = new TransactionRequestImpl();
			final TransactionResponseImpl response = new TransactionResponseImpl();

			final Map<String, XAResourceArchive> participants = transaction.getParticipantMap();
			beanRegistry.setLoadBalancerInterceptor(new TransactionLoadBalancerInterceptor() {
				public List<Server> beforeCompletion(List<Server> servers) {
					final List<Server> readyServerList = new ArrayList<Server>();
					final List<Server> unReadyServerList = new ArrayList<Server>();

					for (int i = 0; servers != null && i < servers.size(); i++) {
						Server server = servers.get(i);
						MetaInfo metaInfo = server.getMetaInfo();
						String instanceId = metaInfo.getInstanceId();

						if (participants.containsKey(instanceId)) {
							List<Server> serverList = new ArrayList<Server>();
							serverList.add(server);
							return serverList;
						} // end-if (participants.containsKey(instanceId))

						if (server.isReadyToServe()) {
							readyServerList.add(server);
						} else {
							unReadyServerList.add(server);
						}

					}

					logger.warn("There is no suitable server: expect= {}, actual= {}!", participants.keySet(), servers);
					return readyServerList.isEmpty() ? unReadyServerList : readyServerList;
				}

				public void afterCompletion(Server server) {
					beanRegistry.removeLoadBalancerInterceptor();

					if (server == null) {
						logger.warn(
								"There is no suitable server, the TransactionInterceptor.beforeSendRequest() operation is not executed!");
						return;
					} // end-if (server == null)

					// TransactionRequestImpl request = new TransactionRequestImpl();
					request.setTransactionContext(transactionContext);

					MetaInfo metaInfo = server.getMetaInfo();
					String identifier = metaInfo.getInstanceId();
					RemoteCoordinator coordinator = beanRegistry.getConsumeCoordinator(identifier);
					request.setTargetTransactionCoordinator(coordinator);

					transactionInterceptor.beforeSendRequest(request);
				}
			});

			try {
				return this.handlers.get(method).invoke(args);

				// catch (feign.RetryableException ex) {
				// // Throwable cause = ex.getCause();
				// // boolean participantDelistFlag = cause != null && java.net.ConnectException.class.isInstance(cause);
				// // response.setParticipantDelistFlag(participantDelistFlag);
			} finally {
				if (response.isIntercepted() == false) {
					response.setTransactionContext(transactionContext);

					RemoteCoordinator coordinator = request.getTargetTransactionCoordinator();
					response.setSourceTransactionCoordinator(coordinator);
					response.setParticipantEnlistFlag(request.isParticipantEnlistFlag());

					transactionInterceptor.afterReceiveResponse(response);
				} // end-if (response.isIntercepted() == false)
			}

		}
	}

	public Target<?> getTarget() {
		return target;
	}

	public void setTarget(Target<?> target) {
		this.target = target;
	}

	public Map<Method, MethodHandler> getHandlers() {
		return handlers;
	}

	public void setHandlers(Map<Method, MethodHandler> handlers) {
		this.handlers = handlers;
	}

}
