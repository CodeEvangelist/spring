package org.springframework.context.support.circulareferences;

public class BeanB {

	private BeanA beanA;

	public BeanA getBeanA() {
		return beanA;
	}

	public void setBeanA(BeanA beanA) {
		this.beanA = beanA;
	}
}
