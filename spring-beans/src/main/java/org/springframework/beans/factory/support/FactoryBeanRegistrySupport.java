/*
 * Copyright 2002-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.beans.factory.support;

import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.FactoryBeanNotInitializedException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.lang.Nullable;

/**
 * Support base class for singleton registries which need to handle
 * {@link org.springframework.beans.factory.FactoryBean} instances,
 * integrated with {@link DefaultSingletonBeanRegistry}'s singleton management.
 *
 * <p>Serves as base class for {@link AbstractBeanFactory}.
 *
 * @author Juergen Hoeller
 * @since 2.5.1
 */
public abstract class FactoryBeanRegistrySupport extends DefaultSingletonBeanRegistry {

	/** Cache of singleton objects created by FactoryBeans: FactoryBean name --> object */
	//缓存所有被FactoryBeans创建的单例bean，key是factoryBean的名称，value是对象
	private final Map<String, Object> factoryBeanObjectCache = new ConcurrentHashMap<>(16);


	/**
	 * Determine the type for the given FactoryBean.
	 * @param factoryBean the FactoryBean instance to check
	 * @return the FactoryBean's object type,
	 * or {@code null} if the type cannot be determined yet
	 */
	@Nullable
	protected Class<?> getTypeForFactoryBean(FactoryBean<?> factoryBean) {
		try {
			if (System.getSecurityManager() != null) {
				return AccessController.doPrivileged(
						(PrivilegedAction<Class<?>>) factoryBean::getObjectType, getAccessControlContext());
			}
			else {
				return factoryBean.getObjectType();
			}
		}
		catch (Throwable ex) {
			// Thrown from the FactoryBean's getObjectType implementation.
			logger.warn("FactoryBean threw exception from getObjectType, despite the contract saying " +
					"that it should return null if the type of its object cannot be determined yet", ex);
			return null;
		}
	}

	/**
	 * Obtain an object to expose from the given FactoryBean, if available
	 * in cached form. Quick check for minimal synchronization.
	 * @param beanName the name of the bean
	 * @return the object obtained from the FactoryBean,
	 * or {@code null} if not available
	 */
	@Nullable
	protected Object getCachedObjectForFactoryBean(String beanName) {
		return this.factoryBeanObjectCache.get(beanName);
	}

	/**
	 * Obtain an object to expose from the given FactoryBean.
	 *
	 * 从给定的FactoryBean获取要公开的对象
	 *
	 * @param factory the FactoryBean instance  factory实例
	 * @param beanName the name of the bean     bean的名称
	 * @param shouldPostProcess whether the bean is subject to post-processing 是否需要bean后置处理
	 * @return the object obtained from the FactoryBean
	 * @throws BeanCreationException if FactoryBean object creation failed
	 * @see org.springframework.beans.factory.FactoryBean#getObject()
	 */
	protected Object getObjectFromFactoryBean(FactoryBean<?> factory, String beanName, boolean shouldPostProcess) {
		//为什么这里要区分单例？
		//从if-else分支可以看到，如果是单例的会缓存下来，否则不会做缓存
		if (factory.isSingleton() && containsSingleton(beanName)) {
			synchronized (getSingletonMutex()) {
				Object object = this.factoryBeanObjectCache.get(beanName);
				if (object == null) {
					//从FactoryBean中获取真正的bean实例
					object = doGetObjectFromFactoryBean(factory, beanName);
					// Only post-process and store if not put there already during getObject() call above
					// (e.g. because of circular reference processing triggered by custom getBean calls)
					//这里再次查询一下，看是不是有并发操作
					Object alreadyThere = this.factoryBeanObjectCache.get(beanName);
					//如果已经有了，那么以前面缓存的为主，把当前的引用指向之前初始化的实例
					if (alreadyThere != null) {
						object = alreadyThere;
					}
					else {
						//判断是否需要对bean进行后置处理
						if (shouldPostProcess) {
							if (isSingletonCurrentlyInCreation(beanName)) {
								// Temporarily return non-post-processed object, not storing it yet..
								return object;
							}
							//后置之前
							beforeSingletonCreation(beanName);
							try {
								//后置处理
								object = postProcessObjectFromFactoryBean(object, beanName);
							}
							catch (Throwable ex) {
								throw new BeanCreationException(beanName,
										"Post-processing of FactoryBean's singleton object failed", ex);
							}
							//后置处理之后
							finally {
								afterSingletonCreation(beanName);
							}
						}
						//缓存
						if (containsSingleton(beanName)) {
							this.factoryBeanObjectCache.put(beanName, object);
						}
					}
				}
				return object;
			}
		}
		//这里没有缓存
		else {
			Object object = doGetObjectFromFactoryBean(factory, beanName);
			if (shouldPostProcess) {
				try {
					//后置处理
					object = postProcessObjectFromFactoryBean(object, beanName);
				}
				catch (Throwable ex) {
					throw new BeanCreationException(beanName, "Post-processing of FactoryBean's object failed", ex);
				}
			}
			return object;
		}
	}

	/**
	 * Obtain an object to expose from the given FactoryBean.
	 * 调用{@linkplain FactoryBean#getObject()}获取真正的bean实例
	 *
	 * @param factory the FactoryBean instance
	 * @param beanName the name of the bean
	 * @return the object obtained from the FactoryBean
	 * @throws BeanCreationException if FactoryBean object creation failed
	 * @see org.springframework.beans.factory.FactoryBean#getObject()
	 */
	private Object doGetObjectFromFactoryBean(FactoryBean<?> factory, String beanName) throws BeanCreationException {
		Object object;
		try {
			//先校验安全性，反正最终都是执行了factory的getObject方法
			if (System.getSecurityManager() != null) {
				AccessControlContext acc = getAccessControlContext();
				try {
					//这里就用到上面createBean处设置的回调函数
					object = AccessController.doPrivileged((PrivilegedExceptionAction<Object>) factory::getObject, acc);
				}
				catch (PrivilegedActionException pae) {
					throw pae.getException();
				}
			}
			else {
				object = factory.getObject();
			}
		}
		catch (FactoryBeanNotInitializedException ex) {
			throw new BeanCurrentlyInCreationException(beanName, ex.toString());
		}
		catch (Throwable ex) {
			throw new BeanCreationException(beanName, "FactoryBean threw exception on object creation", ex);
		}

		// Do not accept a null value for a FactoryBean that's not fully
		// initialized yet: Many FactoryBeans just return null then.
		if (object == null) {
			if (isSingletonCurrentlyInCreation(beanName)) {
				throw new BeanCurrentlyInCreationException(
						beanName, "FactoryBean which is currently in creation returned null from getObject");
			}
			object = new NullBean();
		}
		return object;
	}

	/**
	 * Post-process the given object that has been obtained from the FactoryBean.
	 * The resulting object will get exposed for bean references.
	 * <p>The default implementation simply returns the given object as-is.
	 * Subclasses may override this, for example, to apply post-processors.
	 *
	 * 其实就是看bean实例有没有实现{@linkplain BeanPostProcessor}接口，实现了就需要调用一下相应的重写方法
	 * @param object the object obtained from the FactoryBean.
	 * @param beanName the name of the bean
	 * @return the object to expose
	 * @throws org.springframework.beans.BeansException if any post-processing failed
	 */
	protected Object postProcessObjectFromFactoryBean(Object object, String beanName) throws BeansException {
		return object;
	}

	/**
	 * Get a FactoryBean for the given bean if possible.
	 * @param beanName the name of the bean
	 * @param beanInstance the corresponding bean instance
	 * @return the bean instance as FactoryBean
	 * @throws BeansException if the given bean cannot be exposed as a FactoryBean
	 */
	protected FactoryBean<?> getFactoryBean(String beanName, Object beanInstance) throws BeansException {
		if (!(beanInstance instanceof FactoryBean)) {
			throw new BeanCreationException(beanName,
					"Bean instance of type [" + beanInstance.getClass() + "] is not a FactoryBean");
		}
		return (FactoryBean<?>) beanInstance;
	}

	/**
	 * Overridden to clear the FactoryBean object cache as well.
	 */
	@Override
	protected void removeSingleton(String beanName) {
		synchronized (getSingletonMutex()) {
			super.removeSingleton(beanName);
			this.factoryBeanObjectCache.remove(beanName);
		}
	}

	/**
	 * Overridden to clear the FactoryBean object cache as well.
	 */
	@Override
	protected void clearSingletonCache() {
		synchronized (getSingletonMutex()) {
			super.clearSingletonCache();
			this.factoryBeanObjectCache.clear();
		}
	}

	/**
	 * Return the security context for this bean factory. If a security manager
	 * is set, interaction with the user code will be executed using the privileged
	 * of the security context returned by this method.
	 * @see AccessController#getContext()
	 */
	protected AccessControlContext getAccessControlContext() {
		return AccessController.getContext();
	}

}
