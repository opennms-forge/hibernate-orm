/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.engine.jdbc.internal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.hibernate.AssertionFailure;
import org.hibernate.ScrollMode;
import org.hibernate.engine.jdbc.spi.JdbcServices;
import org.hibernate.engine.jdbc.spi.SqlExceptionHelper;
import org.hibernate.engine.jdbc.spi.StatementPreparer;
import org.hibernate.event.jfr.internal.JfrEventManager;
import org.hibernate.event.jfr.JdbcPreparedStatementCreationEvent;
import org.hibernate.resource.jdbc.spi.JdbcObserver;
import org.hibernate.resource.jdbc.spi.JdbcSessionContext;
import org.hibernate.resource.jdbc.spi.LogicalConnectionImplementor;

/**
 * Standard implementation of {@link StatementPreparer}.
 *
 * @author Steve Ebersole
 * @author Lukasz Antoniak
 * @author Brett Meyer
*/
@SuppressWarnings("resource")
class StatementPreparerImpl implements StatementPreparer {
	private final JdbcCoordinatorImpl jdbcCoordinator;
	private final JdbcServices jdbcServices;

	/**
	 * Construct a StatementPreparerImpl
	 *
	 * @param jdbcCoordinator The JdbcCoordinatorImpl
	 */
	StatementPreparerImpl(JdbcCoordinatorImpl jdbcCoordinator, JdbcServices jdbcServices) {
		this.jdbcCoordinator = jdbcCoordinator;
		this.jdbcServices = jdbcServices;
	}

	protected final JdbcSessionContext settings() {
		return jdbcCoordinator.getJdbcSessionOwner().getJdbcSessionContext();
	}

	protected final Connection connection() {
		return logicalConnection().getPhysicalConnection();
	}

	protected final LogicalConnectionImplementor logicalConnection() {
		return jdbcCoordinator.getLogicalConnection();
	}

	protected final SqlExceptionHelper sqlExceptionHelper() {
		return jdbcServices.getSqlExceptionHelper();
	}
	
	@Override
	public Statement createStatement() {
		try {
			final Statement statement = connection().createStatement();
			jdbcCoordinator.getLogicalConnection().getResourceRegistry().register( statement, true );
			return statement;
		}
		catch ( SQLException e ) {
			throw sqlExceptionHelper().convert( e, "could not create statement" );
		}
	}

	@Override
	public PreparedStatement prepareStatement(String sql) {
		return buildPreparedStatementPreparationTemplate( sql, false ).prepareStatement();
	}

	@Override
	public PreparedStatement prepareStatement(String sql, final boolean isCallable) {
		jdbcCoordinator.executeBatch();
		return buildPreparedStatementPreparationTemplate( sql, isCallable ).prepareStatement();
	}

	private StatementPreparationTemplate buildPreparedStatementPreparationTemplate(String sql, final boolean isCallable) {
		return new StatementPreparationTemplate( sql ) {
			@Override
			protected PreparedStatement doPrepare() throws SQLException {
				return isCallable
						? connection().prepareCall( sql )
						: connection().prepareStatement( sql );
			}
		};
	}

	private void checkAutoGeneratedKeysSupportEnabled() {
		if ( ! settings().isGetGeneratedKeysEnabled() ) {
			throw new AssertionFailure( "getGeneratedKeys() support is not enabled" );
		}
	}

	@Override
	public PreparedStatement prepareStatement(String sql, final int autoGeneratedKeys) {
		if ( autoGeneratedKeys == PreparedStatement.RETURN_GENERATED_KEYS ) {
			checkAutoGeneratedKeysSupportEnabled();
		}
		jdbcCoordinator.executeBatch();
		return new StatementPreparationTemplate( sql ) {
			public PreparedStatement doPrepare() throws SQLException {
				return connection().prepareStatement( sql, autoGeneratedKeys );
			}
		}.prepareStatement();
	}

	@Override
	public PreparedStatement prepareStatement(String sql, final String[] columnNames) {
		checkAutoGeneratedKeysSupportEnabled();
		jdbcCoordinator.executeBatch();
		return new StatementPreparationTemplate( sql ) {
			public PreparedStatement doPrepare() throws SQLException {
				return connection().prepareStatement( sql, columnNames );
			}
		}.prepareStatement();
	}

	@Override
	public PreparedStatement prepareQueryStatement(
			String sql,
			final boolean isCallable,
			final ScrollMode scrollMode) {
		if ( scrollMode != null && !scrollMode.equals( ScrollMode.FORWARD_ONLY ) ) {
			if ( ! settings().isScrollableResultSetsEnabled() ) {
				throw new AssertionFailure("scrollable result sets are not enabled");
			}
			final PreparedStatement ps = new QueryStatementPreparationTemplate( sql ) {
				public PreparedStatement doPrepare() throws SQLException {
						return isCallable
								? connection().prepareCall( sql, scrollMode.toResultSetType(), ResultSet.CONCUR_READ_ONLY )
								: connection().prepareStatement( sql, scrollMode.toResultSetType(), ResultSet.CONCUR_READ_ONLY );
				}
			}.prepareStatement();
			jdbcCoordinator.registerLastQuery( ps );
			return ps;
		}
		else {
			final PreparedStatement ps = new QueryStatementPreparationTemplate( sql ) {
				public PreparedStatement doPrepare() throws SQLException {
						return isCallable
								? connection().prepareCall( sql )
								: connection().prepareStatement( sql );
				}
			}.prepareStatement();
			jdbcCoordinator.registerLastQuery( ps );
			return ps;
		}
	}

	private abstract class StatementPreparationTemplate {
		protected final String sql;

		protected StatementPreparationTemplate(String incomingSql) {
			final String inspectedSql = jdbcCoordinator.getJdbcSessionOwner()
					.getJdbcSessionContext()
					.getStatementInspector()
					.inspect( incomingSql );
			this.sql = inspectedSql == null ? incomingSql : inspectedSql;
		}

		public PreparedStatement prepareStatement() {
			try {
				jdbcServices.getSqlStatementLogger().logStatement( sql );

				final PreparedStatement preparedStatement;
				final JdbcObserver observer = jdbcCoordinator.getJdbcSessionOwner().getJdbcSessionContext().getObserver();
				final JdbcPreparedStatementCreationEvent jdbcPreparedStatementCreation = JfrEventManager.beginJdbcPreparedStatementCreationEvent();
				try {
					observer.jdbcPrepareStatementStart();
					preparedStatement = doPrepare();
					setStatementTimeout( preparedStatement );
				}
				finally {
					JfrEventManager.completeJdbcPreparedStatementCreationEvent( jdbcPreparedStatementCreation, sql );
					observer.jdbcPrepareStatementEnd();
				}
				postProcess( preparedStatement );
				return preparedStatement;
			}
			catch ( SQLException e ) {
				throw sqlExceptionHelper().convert( e, "could not prepare statement", sql );
			}
		}

		protected abstract PreparedStatement doPrepare() throws SQLException;

		public void postProcess(PreparedStatement preparedStatement) throws SQLException {
			jdbcCoordinator.getLogicalConnection().getResourceRegistry().register( preparedStatement, true );
//			logicalConnection().notifyObserversStatementPrepared();
		}

		private void setStatementTimeout(PreparedStatement preparedStatement) throws SQLException {
			final int remainingTransactionTimeOutPeriod = jdbcCoordinator.determineRemainingTransactionTimeOutPeriod();
			if ( remainingTransactionTimeOutPeriod > 0 ) {
				preparedStatement.setQueryTimeout( remainingTransactionTimeOutPeriod );
			}
		}
	}

	private abstract class QueryStatementPreparationTemplate extends StatementPreparationTemplate {
		protected QueryStatementPreparationTemplate(String sql) {
			super( sql );
		}

		public void postProcess(PreparedStatement preparedStatement) throws SQLException {
			super.postProcess( preparedStatement );
			setStatementFetchSize( preparedStatement );
		}
	}

	private void setStatementFetchSize(PreparedStatement statement) throws SQLException {
		if ( settings().getFetchSizeOrNull() != null ) {
			statement.setFetchSize( settings().getFetchSizeOrNull() );
		}
	}

}
