/*
 * Copyright 2004-2007 the original author or authors.
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
package org.springframework.webflow.support.persistence;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import junit.framework.TestCase;

import org.hibernate.HibernateException;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.hibernate3.HibernateCallback;
import org.springframework.orm.hibernate3.HibernateTemplate;
import org.springframework.orm.hibernate3.LocalSessionFactoryBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.webflow.engine.EndState;
import org.springframework.webflow.execution.FlowExecutionException;
import org.springframework.webflow.execution.ViewSelection;
import org.springframework.webflow.test.MockFlowSession;
import org.springframework.webflow.test.MockRequestContext;

/**
 * Tests for {@link HibernateSessionPerConversationListener}
 * 
 * @author Ben Hale
 */
public class HibernateSessionPerConversationListenerTests extends TestCase {

    private SessionFactory sessionFactory;

    private JdbcTemplate jdbcTemplate;

    private HibernateTemplate hibernateTemplate;

    private HibernateSessionPerConversationListener listener;

    protected void setUp() throws Exception {
	DataSource dataSource = getDataSource();
	populateDataBase(dataSource);
	jdbcTemplate = new JdbcTemplate(dataSource);
	sessionFactory = getSessionFactory(dataSource);
	hibernateTemplate = new HibernateTemplate(sessionFactory);
	hibernateTemplate.setCheckWriteOperations(false);
	listener = new HibernateSessionPerConversationListener(sessionFactory);
    }

    public void testSameSession() {
	MockRequestContext context = new MockRequestContext();
	MockFlowSession flowSession = new MockFlowSession();
	flowSession.getDefinitionInternal().getAttributeMap().put("persistenceContext", "true");
	listener.sessionCreated(context, flowSession);
	assertSessionBound();

	// Session created and bound to conversation
	final Session hibSession = (Session) context.getConversationScope().get("hibernate.session");
	assertNotNull("Should have been populated", hibSession);
	listener.paused(context, ViewSelection.NULL_VIEW);
	assertSessionNotBound();

	// Session bound to thread local variable
	listener.resumed(context);
	assertSessionBound();

	hibernateTemplate.execute(new HibernateCallback() {
	    public Object doInHibernate(Session session) throws HibernateException, SQLException {
		assertSame("Should have been original instance", hibSession, session);
		return null;
	    }
	}, true);
	listener.paused(context, ViewSelection.NULL_VIEW);
	assertSessionNotBound();
    }

    public void testFlowNotAPersistenceContext() {
	MockRequestContext context = new MockRequestContext();
	MockFlowSession flowSession = new MockFlowSession();
	listener.sessionCreated(context, flowSession);
	assertSessionNotBound();
    }

    public void testFlowEndsInSingleRequest() {
	assertEquals("Table should only have one row", 1, jdbcTemplate.queryForInt("select count(*) from T_BEAN"));
	MockRequestContext context = new MockRequestContext();
	MockFlowSession flowSession = new MockFlowSession();
	flowSession.getDefinitionInternal().getAttributeMap().put("persistenceContext", "true");
	listener.sessionCreated(context, flowSession);
	assertSessionBound();

	TestBean bean = new TestBean("Keith Donald");
	hibernateTemplate.save(bean);
	assertEquals("Table should still only have one row", 1, jdbcTemplate.queryForInt("select count(*) from T_BEAN"));

	listener.sessionEnded(context, flowSession, null);
	assertEquals("Table should only have two rows", 2, jdbcTemplate.queryForInt("select count(*) from T_BEAN"));
	assertSessionNotBound();
	assertFalse(flowSession.getScope().contains("hibernate.session"));
    }

    public void testFlowSpansMultipleRequests() {
	assertEquals("Table should only have one row", 1, jdbcTemplate.queryForInt("select count(*) from T_BEAN"));
	MockRequestContext context = new MockRequestContext();
	MockFlowSession flowSession = new MockFlowSession();
	flowSession.getDefinitionInternal().getAttributeMap().put("persistenceContext", "true");
	listener.sessionCreated(context, flowSession);
	assertSessionBound();

	TestBean bean1 = new TestBean("Keith Donald");
	hibernateTemplate.save(bean1);
	assertEquals("Table should still only have one row", 1, jdbcTemplate.queryForInt("select count(*) from T_BEAN"));
	listener.paused(context, ViewSelection.NULL_VIEW);
	assertSessionNotBound();

	listener.resumed(context);
	TestBean bean2 = new TestBean("Keith Donald");
	hibernateTemplate.save(bean2);
	assertEquals("Table should still only have one row", 1, jdbcTemplate.queryForInt("select count(*) from T_BEAN"));
	assertSessionBound();

	listener.sessionEnded(context, flowSession, null);
	assertEquals("Table should only have three rows", 3, jdbcTemplate.queryForInt("select count(*) from T_BEAN"));
	assertFalse(flowSession.getScope().contains("hibernate.session"));

	assertSessionNotBound();
	assertFalse(flowSession.getScope().contains("hibernate.session"));

    }

    public void testExceptionThrown() {
	assertEquals("Table should only have one row", 1, jdbcTemplate.queryForInt("select count(*) from T_BEAN"));
	MockRequestContext context = new MockRequestContext();
	MockFlowSession flowSession = new MockFlowSession();
	flowSession.getDefinitionInternal().getAttributeMap().put("persistenceContext", "true");
	listener.sessionCreated(context, flowSession);
	assertSessionBound();

	TestBean bean1 = new TestBean("Keith Donald");
	hibernateTemplate.save(bean1);
	assertEquals("Table should still only have one row", 1, jdbcTemplate.queryForInt("select count(*) from T_BEAN"));
	listener.exceptionThrown(context, new FlowExecutionException("bla", "bla", "bla"));
	assertEquals("Table should still only have one row", 1, jdbcTemplate.queryForInt("select count(*) from T_BEAN"));
	assertSessionNotBound();

    }

    public void testCancelEndState() {
	assertEquals("Table should only have one row", 1, jdbcTemplate.queryForInt("select count(*) from T_BEAN"));
	MockRequestContext context = new MockRequestContext();
	MockFlowSession flowSession = new MockFlowSession();
	flowSession.getDefinitionInternal().getAttributeMap().put("persistenceContext", "true");
	listener.sessionCreated(context, flowSession);
	assertSessionBound();

	TestBean bean = new TestBean("Keith Donald");
	hibernateTemplate.save(bean);
	assertEquals("Table should still only have one row", 1, jdbcTemplate.queryForInt("select count(*) from T_BEAN"));

	EndState endState = new EndState(flowSession.getDefinitionInternal(), "cancel");
	endState.getAttributeMap().put("commit", Boolean.FALSE);
	flowSession.setState(endState);
	listener.sessionEnded(context, flowSession, null);
	assertEquals("Table should only have two rows", 1, jdbcTemplate.queryForInt("select count(*) from T_BEAN"));
	assertSessionNotBound();
	assertFalse(flowSession.getScope().contains("hibernate.session"));
    }

    private DataSource getDataSource() {
	DriverManagerDataSource dataSource = new DriverManagerDataSource();
	dataSource.setDriverClassName("org.hsqldb.jdbcDriver");
	dataSource.setUrl("jdbc:hsqldb:mem:hspcl");
	dataSource.setUsername("sa");
	dataSource.setPassword("");
	return dataSource;
    }

    private void populateDataBase(DataSource dataSource) {
	Connection connection = null;
	try {
	    connection = dataSource.getConnection();
	    connection.createStatement().execute("drop table T_BEAN if exists;");
	    connection.createStatement().execute(
		    "create table T_BEAN (ID integer primary key, NAME varchar(50) not null);");
	    connection.createStatement().execute("insert into T_BEAN (ID, NAME) values (0, 'Ben Hale');");
	} catch (SQLException e) {
	    throw new RuntimeException("SQL exception occurred acquiring connection", e);
	} finally {
	    if (connection != null) {
		try {
		    connection.close();
		} catch (SQLException e) {
		}
	    }
	}
    }

    private SessionFactory getSessionFactory(DataSource dataSource) throws Exception {
	LocalSessionFactoryBean factory = new LocalSessionFactoryBean();
	factory.setDataSource(dataSource);
	factory.setMappingLocations(new Resource[] { new ClassPathResource(
		"org/springframework/webflow/support/persistence/TestBean.hbm.xml") });
	factory.afterPropertiesSet();
	return (SessionFactory) factory.getObject();
    }

    private void assertSessionNotBound() {
	assertNull(TransactionSynchronizationManager.getResource(sessionFactory));
    }

    private void assertSessionBound() {
	assertNotNull(TransactionSynchronizationManager.getResource(sessionFactory));
    }

}