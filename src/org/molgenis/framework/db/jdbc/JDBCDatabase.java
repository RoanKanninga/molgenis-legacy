package org.molgenis.framework.db.jdbc;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import javax.persistence.EntityManager;
import javax.sql.DataSource;

import org.apache.commons.dbcp.BasicDataSource;
import org.apache.log4j.Logger;
import org.molgenis.MolgenisOptions;
import org.molgenis.framework.db.Database;
import org.molgenis.framework.db.DatabaseException;
import org.molgenis.framework.db.JoinQuery;
import org.molgenis.framework.db.Query;
import org.molgenis.framework.db.QueryImp;
import org.molgenis.framework.db.QueryRule;
import org.molgenis.framework.security.Login;
import org.molgenis.util.CsvReader;
import org.molgenis.util.CsvWriter;
import org.molgenis.util.Entity;
import org.molgenis.util.ResultSetTuple;
import org.molgenis.util.SimpleTuple;
import org.molgenis.util.Tuple;

/**
 * JDBC implementation of Database to query relational databases.
 * <p>
 * In order to function, {@link org.molgenis.framework.db.jdbc.JDBCMapper} must
 * be added for each {@link org.molgenis.util.Entity} E that can be queried.
 * These mappers take care of the conversion of Java Entity to the relational
 * database tables using the SQL specific to that source. Typically, these
 * mappers are generated by subclassing JDBCDatabase:
 * 
 * <pre>
 * public JDBCDatabase(DataSource data_src, File file_src)
 * 		throws DatabaseException
 * {
 * 	super(data_src, file_src);
 * 	this.putMapper(Experiment.class, new ExperimentMapper());
 * 	this.putMapper(Assay.class, new AssayMapper());
 * 	this.putMapper(Data.class, new DataMapper());
 * 	this.putMapper(Protocol.class, new ProtocolMapper());
 * 	this.putMapper(Item.class, new ItemMapper());
 * 	this.putMapper(Subject.class, new SubjectMapper());
 * }
 * </pre>
 */
public abstract class JDBCDatabase extends JDBCConnectionHelper implements Database
{
	/** batch size */
	static final int BATCH_SIZE = 5000;
	/** List of mappers, mapping entities to a JDBC connection */
	Map<String, JDBCMapper<? extends Entity>> mappers = new TreeMap<String, JDBCMapper<? extends Entity>>();
	/** The filesource associated to this database: takes care of "file" fields */
	File fileSource;
	/** Login object */
	Login login;
	/** Logger for this database */
	private static final transient Logger logger = Logger
			.getLogger(JDBCDatabase.class.getSimpleName());

	/**
	 * Construct a JDBCDatabase to query relational database.
	 * 
	 * @param data_src
	 *            JDBC DataSource that contains the persistent data.
	 * @param file_source
	 *            File directory where file attachements can be stored.
	 * @throws DatabaseException
	 */
	public JDBCDatabase(DataSource data_src, File file_source)
			throws DatabaseException
	{
		super(new SimpleDataSourceWrapper(data_src));

		// optional: requires a fileSource
		if (file_source == null) logger
				.warn("JDBCDatabase: fileSource is missing");
		this.fileSource = file_source;
	}

	public JDBCDatabase(DataSourceWrapper data_src, File file_source) throws DatabaseException
	{
		super(data_src);

		// optional: requires a fileSource
		if (file_source == null) logger
				.warn("JDBCDatabase: fileSource is missing");
		this.fileSource = file_source;
	}

	public JDBCDatabase(MolgenisOptions options)
	{
		BasicDataSource dSource = new BasicDataSource();
		dSource.setDriverClassName(options.db_driver);
		dSource.setUsername(options.db_user);
		dSource.setPassword(options.db_password);
		dSource.setUrl(options.db_uri);

		this.source = new SimpleDataSourceWrapper(dSource);

		File file_source = new File(options.db_filepath);
		this.fileSource = file_source;

		logger.debug("JDBCDatabase(uri=" + options.db_uri + ") created");
	}

	public JDBCDatabase(Properties p)
	{
		BasicDataSource dSource = new BasicDataSource();
		dSource.setDriverClassName(p.getProperty("db_driver"));
		dSource.setUsername(p.getProperty("db_user"));
		dSource.setPassword(p.getProperty("db_password"));
		dSource.setUrl(p.getProperty("db_uri"));

		this.source = new SimpleDataSourceWrapper(dSource);

		File file_source = new File(p.getProperty("db_filepath"));
		this.fileSource = file_source;
	}

	public JDBCDatabase(File propertiesFilePath) throws FileNotFoundException,
			IOException
	{
		Properties p = new Properties();
		p.load(new FileInputStream(propertiesFilePath));

		BasicDataSource dSource = new BasicDataSource();
		dSource.setDriverClassName(p.getProperty("db_driver"));
		dSource.setUsername(p.getProperty("db_user"));
		dSource.setPassword(p.getProperty("db_password"));
		dSource.setUrl(p.getProperty("db_uri"));

		this.source = new SimpleDataSourceWrapper(dSource);

		File file_source = new File(p.getProperty("db_filepath"));
		this.fileSource = file_source;

	}

	public JDBCDatabase(String propertiesFilePath)
			throws FileNotFoundException, IOException
	{

		Properties p = new Properties();
		try
		{
			p.load(new FileInputStream(propertiesFilePath));
		}
		catch (Exception e)
		{
			p.load(ClassLoader.getSystemResourceAsStream(propertiesFilePath));
		}

		BasicDataSource dSource = new BasicDataSource();
		dSource.setDriverClassName(p.getProperty("db_driver"));
		dSource.setUsername(p.getProperty("db_user"));
		dSource.setPassword(p.getProperty("db_password"));
		dSource.setUrl(p.getProperty("db_uri"));

		this.source = new SimpleDataSourceWrapper(dSource);

		File file_source = new File(p.getProperty("db_filepath"));
		this.fileSource = file_source;
	}

	/**
	 * Only use when really needed!
	 * 
	 * @throws DatabaseException
	 * 
	 * @throws DatabaseException
	 */
	public void executeUpdate(String sql) throws DatabaseException
	{
		try
		{
			Statement stmt = connection.createStatement();
			stmt.executeUpdate(sql);
			stmt.close();
		}
		catch (Exception e)
		{
			e.printStackTrace();
			throw new DatabaseException(e);
		}
		finally
		{
			closeConnection();
		}
	}

	/**
	 * Only use when really needed!
	 * 
	 * @throws DatabaseException
	 */
	public List<Tuple> sql(String sql, QueryRule... rules)
			throws DatabaseException
	{

		ResultSet rs;
		try
		{
			String allSql = sql
					+ (rules.length > 0 ? createWhereSql(null, false, true,
							rules) : "");
			rs = executeQuery(allSql);
			// transform result set in entity list
			List<Tuple> tuples = new ArrayList<Tuple>();
			if (rs != null)
			{
				while (rs.next())
				{
					tuples.add(new SimpleTuple(new ResultSetTuple(rs)));
				}
			}
			rs.close();

			logger.info("sql(" + allSql + ")" + tuples.size()
					+ " objects found");
			return tuples;
		}
		catch (Exception e)
		{
			e.printStackTrace();
			throw new DatabaseException(e);
		}
		finally
		{
			closeConnection();
		}
	}

	// @Override
	public <E extends Entity> int count(Class<E> klazz, QueryRule... rules)
			throws DatabaseException
	{
		return getMapperFor(klazz).count(rules);
	}

	// @Override
	public <E extends Entity> List<E> findByExample(E example)
			throws DatabaseException
	{
		try
		{
			Query<E> q = this.query(getClassForEntity(example));
			// add first security rules
			// q.addRules(this.getSecurity().getRowlevelSecurityFilters(example.getClass()));

			for (String field : example.getFields())
			{
				if (example.get(field) != null)
				{
					if (example.get(field) instanceof List<?>)
					{
						if (((List<?>) example.get(field)).size() > 0) q.in(
								field, (List<?>) example.get(field));
					}
					else
						q.equals(field, example.get(field));
				}
			}

			return q.find();
		}
		catch (ParseException pe)
		{
			// should never happen...
			pe.printStackTrace();
		}
		return null;
	}

	// @Override
	public <E extends Entity> List<E> find(Class<E> klazz, QueryRule... rules)
			throws DatabaseException
	{
		// add security filters
		// QueryRule securityRules = null;
		// if (this.getSecurity() != null) securityRules =
		// this.getSecurity().getRowlevelSecurityFilters(klazz);
		// if (securityRules != null)
		// {
		// if (rules != null && rules.length > 1)
		// {
		// List<QueryRule> all = new ArrayList<QueryRule>();
		// all.add(securityRules);
		// all.addAll(Arrays.asList(rules));
		// return getMapperFor(klazz).find(all.toArray(new
		// QueryRule[all.size()]));
		// }
		// return getMapperFor(klazz).find(securityRules);
		// }
		return getMapperFor(klazz).find(rules);
	}

//	private <E extends Entity> QueryRule[] addSecurityFilters(Class<E> klazz,
//			QueryRule... rules)
//	{
//		// add security filters
//		QueryRule securityRules = null;
//
//		// if there is a security system, create the security rules
//		if (getSecurity() != null)
//		{
//			securityRules = getSecurity().getRowlevelSecurityFilters(klazz);
//		}
//		// if the security system returned filters then merge with user rules
//		if (securityRules != null)
//		{
//			// if user rules, merge user rules with security rules
//			if (rules != null && rules.length >= 1)
//			{
//				List<QueryRule> all = new ArrayList<QueryRule>();
//				all.add(securityRules);
//				all.addAll(Arrays.asList(rules));
//				return all.toArray(new QueryRule[all.size()]);
//			}
//			// if no user rules than only return security rules
//			else
//			{
//				return new QueryRule[]
//				{ securityRules };
//			}
//		}
//		// if the security system
//		else
//		{
//			return rules;
//		}
//	}

	// @Override
	public <E extends Entity> void find(Class<E> klazz, CsvWriter writer,
			QueryRule... rules) throws DatabaseException
	{
		getMapperFor(klazz).find(writer, rules);
	}

	// @Override
	public <E extends Entity> void find(Class<E> klazz, CsvWriter writer,
			List<String> fieldsToExport, QueryRule... rules)
			throws DatabaseException
	{
		getMapperFor(klazz).find(writer, fieldsToExport, rules);
	}

	// @Override
	public <E extends Entity> Query<E> query(Class<E> klazz)
	{
		Query<E> q = new QueryImp<E>(this, klazz);
		// if(this.getSecurity().getRowlevelSecurityFilters(klazz) != null)
		// {
		// q.addRules(this.getSecurity().getRowlevelSecurityFilters(klazz));
		// }
		return q;
	}

	// @Override
	public <E extends Entity> int add(E entity) throws DatabaseException,
			IOException
	{
		List<E> entityList = new ArrayList<E>();
		entityList.add(entity);
		return this.add(entityList);
	}

	// @Override
	public <E extends Entity> int add(List<E> entities)
			throws DatabaseException, IOException
	{
		if (entities.size() > 0)
		{
			return getMapperFor(entities).add(entities);
		}
		return 0;
	}

	// @Override
	public <E extends Entity> int add(Class<E> klazz, CsvReader reader,
			CsvWriter writer) throws Exception
	{
		return getMapperFor(klazz).add(reader, writer);
	}

	// @Override
	public <E extends Entity> int update(E entity) throws DatabaseException,
			IOException
	{
		List<E> entityList = new ArrayList<E>();
		entityList.add(entity);
		return this.update(entityList);
	}

	// @Override
	public <E extends Entity> int update(List<E> entities)
			throws DatabaseException, IOException
	{
		if (entities.size() > 0)
		{
			return getMapperFor(entities).update(entities);
		}
		return 0;
	}

	// @Override
	public <E extends Entity> int update(Class<E> klazz, CsvReader reader)
			throws Exception
	{
		return getMapperFor(klazz).update(reader);
	}

	// @Override
	public <E extends Entity> int remove(E entity) throws DatabaseException,
			IOException
	{
		List<E> entityList = new ArrayList<E>();
		entityList.add(entity);
		return this.remove(entityList);
	}

	// @Override
	public <E extends Entity> int remove(List<E> entities)
			throws DatabaseException, IOException
	{
		if (entities.size() > 0)
		{
			return getMapperFor(entities).remove(entities);
		}
		return 0;
	}

	// @Override
	public <E extends Entity> int remove(Class<E> klazz, CsvReader reader)
			throws Exception
	{
		return getMapperFor(klazz).remove(reader);
	}

	// @Override
	public File getFilesource()
	{
		return this.fileSource;
	}

	/**
	 * Find the mapper from this.mappers
	 * 
	 * @param klazz
	 *            the entity class to get the mapper from
	 * @return a mapper or a exception
	 * @throws SQLException
	 */
	@SuppressWarnings("unchecked")
	private <E extends Entity> JDBCMapper<E> getMapperFor(Class<E> klazz)
			throws DatabaseException
	{
		// transform to generic exception
		JDBCMapper<? extends Entity> mapper = mappers.get(klazz.getName());
		if (mapper == null) throw new DatabaseException(
				"getMapperFor failed because no mapper available for "
						+ klazz.getName());
		return (JDBCMapper<E>) mapper;
	}

	/**
	 * Assign a mapper for a certain class.
	 * 
	 * <pre>
	 * putMapper(Example.class, new ExampleMapper());
	 * </pre>
	 * 
	 * @param klazz
	 *            the class of this Entity
	 * @param mapper
	 */
	protected <E extends Entity> void putMapper(Class<E> klazz,
			JDBCMapper<E> mapper)
	{
		this.mappers.put(klazz.getName(), mapper);
		// logger.debug("added mapper for klazz " + klazz.getName());
	}

	// @Override
	public List<String> getEntityNames()
	{
		List<String> entities = new ArrayList<String>();
		entities.addAll(mappers.keySet());
		return entities;
	}

	// @Override
	public <E extends Entity> List<E> toList(Class<E> klazz, CsvReader reader,
			int limit) throws Exception
	{
		return getMapperFor(klazz).toList(reader, limit);
	}

	// @Override
	public void close() throws DatabaseException
	{
		closeConnection();
	}

	/**
	 * Used to put large transactions in batches while containing transaction
	 * integrity.
	 * 
	 * @param ticket
	 *            name for the private transaction (to make sure the tx owner
	 *            commits it)
	 * @throws DatabaseException
	 */
	void beginPrivateTx(String ticket) throws DatabaseException
	{
		if (!this.inTransaction)
		{
			this.privateTransaction = ticket;
			this.beginTx();
			// logger.debug("Begin private TX '" + ticket + "'");
		}
		else
		{
			// logger.debug("Trying to start private TX '"+ticket+
			// "' but already started with '"+this.privateTransaction+"'");
		}
	}

	/**
	 * Used to put large transactions in batches while containing transaction
	 * integrity.
	 * 
	 * @param ticket
	 *            name for the private transaction (to make sure the tx owner
	 *            commits it)
	 * @throws DatabaseException
	 */
	protected void commitPrivateTx(String ticket) throws DatabaseException
	{
		if (ticket != null && ticket.equals(this.privateTransaction))
		{
			this.commitTx();
			this.privateTransaction = null;
			// logger.debug("Commit private TX '" + ticket + "'");
		}
		else
		{
			// logger.debug("Trying to commit private TX '"+ticket+"' but
			// another still running with '"+this.privateTransaction+"'");
		}
	}

	/**
	 * Used to put large transactions in batches while containing transaction
	 * integrity.
	 * 
	 * @param ticket
	 *            name for the private transaction (to make sure the tx owner
	 *            commits it)
	 * @throws DatabaseException
	 */
	protected void rollbackPrivateTx(String ticket) throws DatabaseException
	{
		if (ticket != null && ticket.equals(this.privateTransaction))
		{
			this.rollbackTx();
			this.privateTransaction = null;
			// logger.debug("Rollback private TX '" + ticket + "'");
		}
		else
		{
			// logger.debug("Trying to rollback private TX '"+ticket+"' but
			// another still running with '"+this.privateTransaction+"'");
		}
	}

	/**
	 * Local helper to find mappers for lists.
	 * 
	 * @param entities
	 *            list of entities to find this mapper for.
	 * @return a mapper or a exception
	 * @throws SQLException
	 */
	@SuppressWarnings("unchecked")
	private <E extends Entity> JDBCMapper<E> getMapperFor(List<E> entities)
			throws DatabaseException
	{
		try
		{
			@SuppressWarnings("rawtypes")
			Class klazz = entities.get(0).getClass();
			return getMapperFor(klazz);
		}
		catch (NullPointerException e)
		{
			// transform to generic exception
			logger.error("trying to store empty list");
			throw new DatabaseException(
					"getMapperFor failed because of empty list");
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public List<Class<? extends Entity>> getEntityClasses()
	{

		List<Class<? extends Entity>> classes = new ArrayList<Class<? extends Entity>>();
		try
		{
			for (String klazz : this.getEntityNames())
			{
				classes.add((Class<? extends Entity>) Class.forName(klazz));
			}
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}
		return classes;
	}

	@Override
	public Class<? extends Entity> getClassForName(String name)
	{
		for (Class<? extends Entity> c : this.getEntityClasses())
		{
			if (c.getSimpleName().toLowerCase().equals(name.toLowerCase())) return c;
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private <E extends Entity> Class<E> getClassForEntity(E entity)
	{
		return (Class<E>) entity.getClass();
	}

	public Login getSecurity()
	{
		return login;
	}

	public void setLogin(Login login)
	{
		this.login = login;
	}

	@Override
	public JoinQuery join(Class<? extends Entity>... classes)
			throws DatabaseException
	{
		return new JoinQuery(this, classes);
	}

	@Override
	public <E extends Entity> E findById(Class<E> klazz, Object id)
			throws DatabaseException
	{
		JDBCMapper<E> mapper = getMapperFor(klazz);
		List<E> result = (List<E>) mapper.find(new QueryRule(mapper.create()
				.getIdField(), QueryRule.Operator.EQUALS, id));
		if (result.size() > 0) return result.get(0);
		return null;
	}

	public <E extends Entity> void matchByNameAndUpdateFields(
			List<E> existingEntities, List<E> entities) throws ParseException
	{
		// List<E> updatedDbEntities = new ArrayList<E>();
		for (E entityInDb : existingEntities)
		{
			for (E newEntity : entities)
			{
				// FIXME very wrong! this assumes every data model has 'name' as
				// secondary key.
				boolean match = false;
				// check if there are any label fields otherwise check
				// impossible
				if (entityInDb.getLabelFields().size() > 0) match = true;
				for (String labelField : entityInDb.getLabelFields())
				{
					if (!entityInDb.get(labelField).equals(
							newEntity.get(labelField)))
					{
						match = false;
						break;
					}
				}
				if (match)
				{
					Tuple newValues = new SimpleTuple();
					for (String field : newEntity.getFields())
					{
						// as they are new entities, should include 'id'
						if (!(newEntity.get(field) == null))
						{
							// logger.debug("entity name = " +
							// newEntity.get("name") + " has null field: " +
							// field);
							newValues.set(field, newEntity.get(field));

						}
					}
					entityInDb.set(newValues, false);
				}
			}
		}
		// return entities;
	}

	@Override
	/**
	 * Requires the keys to be set. In case of ADD we don't require the primary key if autoid.
	 */
	public <E extends Entity> int update(List<E> entities,
			DatabaseAction dbAction, String... keyNames)
			throws DatabaseException, ParseException, IOException
	{
		// nothing todo?
		if (entities.size() == 0) return 0;

		// retrieve entity class and name
		Class<E> entityClass = getClassForEntity(entities.get(0));
		String entityName = entityClass.getSimpleName();

		// create maps to store key values and entities
		// key is a concat of all key values for an entity
		Map<String, E> entityIndex = new LinkedHashMap<String, E>();
		// list of all keys, each list item a map of a (composite) key for one
		// entity e.g. investigation_name + name
		List<Map<String, Object>> keyIndex = new ArrayList<Map<String, Object>>();

		// select existing for update, only works if one (composit key allows
		// for nulls) the key values are set
		// otherwise skipped
		boolean keysMissing = false;
		for (E entity : entities)
		{
			// get all the value of all keys (composite key)
			// use an index to hash the entities
			String combinedKey = "";

			// extract its key values and put in map
			Map<String, Object> keyValues = new LinkedHashMap<String, Object>();
			boolean incompleteKey = true;

			// note: we can expect null values in composite keys but need at
			// least one key value.
			for (String key : keyNames)
			{
				// create a hash that concats all key values into one string
				combinedKey += ";"
						+ (entity.get(key) == null ? "" : entity.get(key));

				// if (entity.get(key) == null || entity.get(key).equals(""))
				// {
				// if (dbAction.equals(DatabaseAction.UPDATE) ||
				// dbAction.equals(DatabaseAction.REMOVE))
				// {
				// throw new DatabaseException(
				// entityName + " is missing key '" + key + "' in line " +
				// entity.toString());
				// }
				// }
				if (entity.get(key) != null)
				{
					incompleteKey = false;
					keyValues.put(key, entity.get(key));
				}
			}
			// check if we have missing key
			if (incompleteKey) keysMissing = true;

			// add the keys to the index, if exists
			if (!keysMissing)
			{
				keyIndex.add(keyValues);
				// create the entity index using the hash
				entityIndex.put(combinedKey, entity);
			}
			else
			{
				if ((dbAction.equals(DatabaseAction.ADD)
						|| dbAction.equals(DatabaseAction.ADD_IGNORE_EXISTING) || dbAction
						.equals(DatabaseAction.ADD_UPDATE_EXISTING))
						&& keyNames.length == 1
						&& keyNames[0].equals(entity.getIdField()))
				{
					// don't complain is 'id' field is emptyr
				}
				else
				{
					throw new DatabaseException("keys are missing: "
							+ entityClass.getSimpleName() + "."
							+ Arrays.asList(keyNames));
				}
			}
		}

		// split lists in new and existing entities, but only if keys are set
		List<E> newEntities = entities;
		List<E> existingEntities = new ArrayList<E>();
		if (!keysMissing && keyIndex.size() > 0)
		{
			newEntities = new ArrayList<E>();
			Query<E> q = this.query(getClassForEntity(entities.get(0)));

			// in case of one field key, simply query
			if (keyNames.length == 1)
			{
				List<Object> values = new ArrayList<Object>();
				for (Map<String, Object> keyValues : keyIndex)
				{
					values.add(keyValues.get(keyNames[0]));
				}
				q.in(keyNames[0], values);
			}
			// in case of composite key make massive 'OR' query
			// form (key1 = x AND key2 = X) OR (key1=y AND key2=y)
			else
			{
				// very expensive!
				for (Map<String, Object> keyValues : keyIndex)
				{
					for (int i = 0; i < keyNames.length; i++)
					{
						if (i > 0) q.or();
						q.equals(keyNames[i], keyValues.get(keyNames[i]));
					}
				}
			}
			List<E> selectForUpdate = q.find();

			// separate existing from new entities
			for (E p : selectForUpdate)
			{
				// reconstruct composite key so we can use the entityIndex
				String combinedKey = "";
				for (String key : keyNames)
				{
					combinedKey += ";" + p.get(key);
				}
				// copy existing from entityIndex to existingEntities
				entityIndex.remove(combinedKey);
				existingEntities.add(p);
			}
			// copy remaining to newEntities
			newEntities = new ArrayList<E>(entityIndex.values());
		}

		// if existingEntities are going to be updated, they will need to
		// receive new values from 'entities' in addition to be mapped to the
		// database as is the case at this point
		if (existingEntities.size() > 0
				&& (dbAction == DatabaseAction.ADD_UPDATE_EXISTING
						|| dbAction == DatabaseAction.UPDATE || dbAction == DatabaseAction.UPDATE_IGNORE_MISSING))
		{
			logger.info("existingEntities[0] before: "
					+ existingEntities.get(0).toString());
			matchByNameAndUpdateFields(existingEntities, entities);
			logger.info("existingEntities[0] after: "
					+ existingEntities.get(0).toString());
		}

		switch (dbAction)
		{

			// will test for existing entities before add
			// (so only add if existingEntities.size == 0).
			case ADD:
				if (existingEntities.size() == 0)
				{
					return add(newEntities);
				}
				else
				{
					throw new DatabaseException("Tried to add existing "
							+ entityName
							+ " elements as new insert: "
							+ Arrays.asList(keyNames)
							+ "="
							+ existingEntities.subList(0, Math.min(5,
									existingEntities.size()))
							+ (existingEntities.size() > 5 ? " and "
									+ (existingEntities.size() - 5) + "more"
									: "" + existingEntities));
				}

				// will not test for existing entities before add
				// (so will ignore existingEntities)
			case ADD_IGNORE_EXISTING:
				logger.debug("updateByName(List<" + entityName + "," + dbAction
						+ ">) will skip " + existingEntities.size()
						+ " existing entities");
				return add(newEntities);

				// will try to update(existingEntities) entities and
				// add(missingEntities)
				// so allows user to be sloppy in adding/updating
			case ADD_UPDATE_EXISTING:
				logger.debug("updateByName(List<" + entityName + "," + dbAction
						+ ">)  will try to update " + existingEntities.size()
						+ " existing entities and add " + newEntities.size()
						+ " new entities");
				return add(newEntities) + update(existingEntities);

				// update while testing for newEntities.size == 0
			case UPDATE:
				if (newEntities.size() == 0)
				{
					return update(existingEntities);
				}
				else
				{
					throw new DatabaseException("Tried to update non-existing "
							+ entityName + "elements "
							+ Arrays.asList(keyNames) + "="
							+ entityIndex.values());
				}

				// update that doesn't test for newEntities but just ignores
				// those
				// (so only updates exsiting)
			case UPDATE_IGNORE_MISSING:
				logger.debug("updateByName(List<" + entityName + "," + dbAction
						+ ">) will try to update " + existingEntities.size()
						+ " existing entities and skip " + newEntities.size()
						+ " new entities");
				return update(existingEntities);

				// remove all elements in list, test if no elements are missing
				// (so test for newEntities == 0)
			case REMOVE:
				if (newEntities.size() == 0)
				{
					logger.debug("updateByName(List<" + entityName + ","
							+ dbAction + ">) will try to remove "
							+ existingEntities.size() + " existing entities");
					return remove(existingEntities);
				}
				else
				{
					throw new DatabaseException("Tried to remove non-existing "
							+ entityName + " elements "
							+ Arrays.asList(keyNames) + "="
							+ entityIndex.values());

				}

				// remove entities that are in the list, ignore if they don't
				// exist in database
				// (so don't check the newEntities.size == 0)
			case REMOVE_IGNORE_MISSING:
				logger.debug("updateByName(List<" + entityName + "," + dbAction
						+ ">) will try to remove " + existingEntities.size()
						+ " existing entities and skip " + newEntities.size()
						+ " new entities");
				return remove(existingEntities);

				// unexpected error
			default:
				throw new DatabaseException(
						"updateByName failed because of unknown dbAction "
								+ dbAction);
		}
	}

	@Override
	public EntityManager getEntityManager()
	{
		throw new UnsupportedOperationException();
	}
}