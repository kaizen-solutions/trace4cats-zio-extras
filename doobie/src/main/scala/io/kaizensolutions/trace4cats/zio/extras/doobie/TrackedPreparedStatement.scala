package io.kaizensolutions.trace4cats.zio.extras.doobie

import java.io.{InputStream, Reader}
import java.net.URL
import java.sql
import java.sql.{Array as _, *}
import java.util.Calendar
import scala.annotation.nowarn
import scala.collection.mutable

/**
 * A wrapper on top of a PreparedStatement that tracks `set` calls to the
 * underlying statement as well associates the statement with the query string
 * that was used to create it
 *
 * @param underlying
 *   is the PreparedStatement that is being wrapped
 * @param queryString
 *   is the SQL query string that was used to create the statement
 */
private[doobie] final class TrackedPreparedStatement(
  val underlying: PreparedStatement,
  val queryString: String
) extends PreparedStatement {
  private val trackedMutations: mutable.SortedMap[Int, Any] = mutable.SortedMap.empty

  def extractMutations: Seq[(Int, Any)] = trackedMutations.toSeq

  override def executeQuery(): ResultSet = underlying.executeQuery()

  override def executeUpdate(): Int = underlying.executeUpdate()

  override def setNull(parameterIndex: Int, sqlType: Int): Unit = {
    trackedMutations += (parameterIndex -> "<null>")
    underlying.setNull(parameterIndex, sqlType)
  }

  override def setBoolean(parameterIndex: Int, x: Boolean): Unit = {
    trackedMutations += (parameterIndex -> x)
    underlying.setBoolean(parameterIndex, x)
  }

  override def setByte(parameterIndex: Int, x: Byte): Unit = {
    trackedMutations += (parameterIndex -> x)
    underlying.setByte(parameterIndex, x)
  }

  override def setShort(parameterIndex: Int, x: Short): Unit = {
    trackedMutations += (parameterIndex -> x)
    underlying.setShort(parameterIndex, x)
  }

  override def setInt(parameterIndex: Int, x: Int): Unit = {
    trackedMutations += (parameterIndex -> x)
    underlying.setInt(parameterIndex, x)
  }

  override def setLong(parameterIndex: Int, x: Long): Unit = {
    trackedMutations += (parameterIndex -> x)
    underlying.setLong(parameterIndex, x)
  }

  override def setFloat(parameterIndex: Int, x: Float): Unit = {
    trackedMutations += (parameterIndex -> x)
    underlying.setFloat(parameterIndex, x)
  }

  override def setDouble(parameterIndex: Int, x: Double): Unit = {
    trackedMutations += (parameterIndex -> x)
    underlying.setDouble(parameterIndex, x)
  }

  override def setBigDecimal(parameterIndex: Int, x: java.math.BigDecimal): Unit = {
    trackedMutations += (parameterIndex -> x)
    underlying.setBigDecimal(parameterIndex, x)
  }

  override def setString(parameterIndex: Int, x: String): Unit = {
    trackedMutations += (parameterIndex -> x)
    underlying.setString(parameterIndex, x)
  }

  override def setBytes(parameterIndex: Int, x: Array[Byte]): Unit = {
    trackedMutations += (parameterIndex -> x)
    underlying.setBytes(parameterIndex, x)
  }

  override def setDate(parameterIndex: Int, x: Date): Unit = {
    trackedMutations += (parameterIndex -> x)
    underlying.setDate(parameterIndex, x)
  }

  override def setTime(parameterIndex: Int, x: Time): Unit = {
    trackedMutations += (parameterIndex -> x)
    underlying.setTime(parameterIndex, x)
  }

  override def setTimestamp(parameterIndex: Int, x: Timestamp): Unit = {
    trackedMutations += (parameterIndex -> x)
    underlying.setTimestamp(parameterIndex, x)
  }

  override def setAsciiStream(parameterIndex: Int, x: InputStream, length: Int): Unit = {
    trackedMutations += (parameterIndex -> "<ascii stream>")
    underlying.setAsciiStream(parameterIndex, x, length)
  }

  @nowarn("cat=deprecation")
  override def setUnicodeStream(parameterIndex: Int, x: InputStream, length: Int): Unit = {
    trackedMutations += (parameterIndex -> "<unicode stream>")
    underlying.setUnicodeStream(parameterIndex, x, length)
  }

  override def setBinaryStream(parameterIndex: Int, x: InputStream, length: Int): Unit = {
    trackedMutations += (parameterIndex -> "<binary stream>")
    underlying.setBinaryStream(parameterIndex, x, length)
  }

  override def clearParameters(): Unit = {
    trackedMutations.clear()
    underlying.clearParameters()
  }

  override def setObject(parameterIndex: Int, x: Any, targetSqlType: Int): Unit = {
    trackedMutations += (parameterIndex -> x)
    underlying.setObject(parameterIndex, x, targetSqlType)
  }

  override def setObject(parameterIndex: Int, x: Any): Unit = {
    trackedMutations += (parameterIndex -> x)
    underlying.setObject(parameterIndex, x)
  }

  override def execute(): Boolean = underlying.execute()

  override def addBatch(): Unit = underlying.addBatch()

  override def setCharacterStream(parameterIndex: Int, reader: Reader, length: Int): Unit = {
    trackedMutations += (parameterIndex -> "<character stream>")
    underlying.setCharacterStream(parameterIndex, reader, length)
  }

  override def setRef(parameterIndex: Int, x: Ref): Unit = {
    trackedMutations += (parameterIndex -> x)
    underlying.setRef(parameterIndex, x)
  }

  override def setBlob(parameterIndex: Int, x: Blob): Unit = {
    trackedMutations += (parameterIndex -> "<blob>")
    underlying.setBlob(parameterIndex, x)
  }

  override def setClob(parameterIndex: Int, x: Clob): Unit = {
    trackedMutations += (parameterIndex -> "<clob>")
    underlying.setClob(parameterIndex, x)
  }

  override def setArray(parameterIndex: Int, x: sql.Array): Unit = {
    trackedMutations += (parameterIndex -> "<array>")
    underlying.setArray(parameterIndex, x)
  }

  override def getMetaData: ResultSetMetaData = underlying.getMetaData

  override def setDate(parameterIndex: Int, x: Date, cal: Calendar): Unit = {
    trackedMutations += (parameterIndex -> x)
    underlying.setDate(parameterIndex, x, cal)
  }

  override def setTime(parameterIndex: Int, x: Time, cal: Calendar): Unit = {
    trackedMutations += (parameterIndex -> x)
    underlying.setTime(parameterIndex, x, cal)
  }

  override def setTimestamp(parameterIndex: Int, x: Timestamp, cal: Calendar): Unit = {
    trackedMutations += (parameterIndex -> x)
    underlying.setTimestamp(parameterIndex, x, cal)
  }

  override def setNull(parameterIndex: Int, sqlType: Int, typeName: String): Unit = {
    trackedMutations += (parameterIndex -> "<null>")
    underlying.setNull(parameterIndex, sqlType, typeName)
  }

  override def setURL(parameterIndex: Int, x: URL): Unit = {
    trackedMutations += (parameterIndex -> x)
    underlying.setURL(parameterIndex, x)
  }

  override def getParameterMetaData: ParameterMetaData = underlying.getParameterMetaData

  override def setRowId(parameterIndex: Int, x: RowId): Unit = {
    trackedMutations += (parameterIndex -> x)
    underlying.setRowId(parameterIndex, x)
  }

  override def setNString(parameterIndex: Int, value: String): Unit = {
    trackedMutations += (parameterIndex -> value)
    underlying.setNString(parameterIndex, value)
  }

  override def setNCharacterStream(parameterIndex: Int, value: Reader, length: Long): Unit = {
    trackedMutations += (parameterIndex -> "<ncharacter stream>")
    underlying.setNCharacterStream(parameterIndex, value, length)
  }

  override def setNClob(parameterIndex: Int, value: NClob): Unit = {
    trackedMutations += (parameterIndex -> "<nclob>")
    underlying.setNClob(parameterIndex, value)
  }

  override def setClob(parameterIndex: Int, reader: Reader, length: Long): Unit = {
    trackedMutations += (parameterIndex -> "<clob>")
    underlying.setClob(parameterIndex, reader, length)
  }

  override def setBlob(parameterIndex: Int, inputStream: InputStream, length: Long): Unit = {
    trackedMutations += (parameterIndex -> "<blob>")
    underlying.setBlob(parameterIndex, inputStream, length)
  }

  override def setNClob(parameterIndex: Int, reader: Reader, length: Long): Unit = {
    trackedMutations += (parameterIndex -> "<nclob>")
    underlying.setNClob(parameterIndex, reader, length)
  }

  override def setSQLXML(parameterIndex: Int, xmlObject: SQLXML): Unit = {
    trackedMutations += (parameterIndex -> "<sqlxml>")
    underlying.setSQLXML(parameterIndex, xmlObject)
  }

  override def setObject(parameterIndex: Int, x: Any, targetSqlType: Int, scaleOrLength: Int): Unit = {
    trackedMutations += (parameterIndex -> x)
    underlying.setObject(parameterIndex, x, targetSqlType, scaleOrLength)
  }

  override def setAsciiStream(parameterIndex: Int, x: InputStream, length: Long): Unit = {
    trackedMutations += (parameterIndex -> "<ascii stream>")
    underlying.setAsciiStream(parameterIndex, x, length)
  }

  override def setBinaryStream(parameterIndex: Int, x: InputStream, length: Long): Unit = {
    trackedMutations += (parameterIndex -> "<binary stream>")
    underlying.setBinaryStream(parameterIndex, x, length)
  }

  override def setCharacterStream(parameterIndex: Int, reader: Reader, length: Long): Unit = {
    trackedMutations += (parameterIndex -> "<character stream>")
    underlying.setCharacterStream(parameterIndex, reader, length)
  }

  override def setAsciiStream(parameterIndex: Int, x: InputStream): Unit = {
    trackedMutations += (parameterIndex -> "<ascii stream>")
    underlying.setAsciiStream(parameterIndex, x)
  }

  override def setBinaryStream(parameterIndex: Int, x: InputStream): Unit = {
    trackedMutations += (parameterIndex -> "<binary stream>")
    underlying.setBinaryStream(parameterIndex, x)
  }

  override def setCharacterStream(parameterIndex: Int, reader: Reader): Unit = {
    trackedMutations += (parameterIndex -> "<character stream>")
    underlying.setCharacterStream(parameterIndex, reader)
  }

  override def setNCharacterStream(parameterIndex: Int, value: Reader): Unit = {
    trackedMutations += (parameterIndex -> "<ncharacter stream>")
    underlying.setNCharacterStream(parameterIndex, value)
  }

  override def setClob(parameterIndex: Int, reader: Reader): Unit = {
    trackedMutations += (parameterIndex -> "<clob>")
    underlying.setClob(parameterIndex, reader)
  }

  override def setBlob(parameterIndex: Int, inputStream: InputStream): Unit = {
    trackedMutations += (parameterIndex -> "<blob>")
    underlying.setBlob(parameterIndex, inputStream)
  }

  override def setNClob(parameterIndex: Int, reader: Reader): Unit = {
    trackedMutations += (parameterIndex -> "<nclob>")
    underlying.setNClob(parameterIndex, reader)
  }

  override def executeQuery(sql: String): ResultSet = underlying.executeQuery(sql)

  override def executeUpdate(sql: String): Int = underlying.executeUpdate(sql)

  override def close(): Unit = underlying.close()

  override def getMaxFieldSize: Int = underlying.getMaxFieldSize

  override def setMaxFieldSize(max: Int): Unit = underlying.setMaxFieldSize(max)

  override def getMaxRows: Int = underlying.getMaxRows

  override def setMaxRows(max: Int): Unit = underlying.setMaxRows(max)

  override def setEscapeProcessing(enable: Boolean): Unit = underlying.setEscapeProcessing(enable)

  override def getQueryTimeout: Int = underlying.getQueryTimeout

  override def setQueryTimeout(seconds: Int): Unit = underlying.setQueryTimeout(seconds)

  override def cancel(): Unit = underlying.cancel()

  override def getWarnings: SQLWarning = underlying.getWarnings

  override def clearWarnings(): Unit = underlying.clearWarnings()

  override def setCursorName(name: String): Unit = underlying.setCursorName(name)

  override def execute(sql: String): Boolean = underlying.execute(sql)

  override def getResultSet: ResultSet = underlying.getResultSet

  override def getUpdateCount: Int = underlying.getUpdateCount

  override def getMoreResults: Boolean = underlying.getMoreResults

  override def setFetchDirection(direction: Int): Unit = underlying.setFetchDirection(direction)

  override def getFetchDirection: Int = underlying.getFetchDirection

  override def setFetchSize(rows: Int): Unit = underlying.setFetchSize(rows)

  override def getFetchSize: Int = underlying.getFetchSize

  override def getResultSetConcurrency: Int = underlying.getResultSetConcurrency

  override def getResultSetType: Int = underlying.getResultSetType

  override def addBatch(sql: String): Unit = underlying.addBatch(sql)

  override def clearBatch(): Unit = underlying.clearBatch()

  override def executeBatch(): Array[Int] = underlying.executeBatch()

  override def getConnection: Connection = underlying.getConnection

  override def getMoreResults(current: Int): Boolean = underlying.getMoreResults(current)

  override def getGeneratedKeys: ResultSet = underlying.getGeneratedKeys

  override def executeUpdate(sql: String, autoGeneratedKeys: Int): Int =
    underlying.executeUpdate(sql, autoGeneratedKeys)

  override def executeUpdate(sql: String, columnIndexes: Array[Int]): Int = underlying.executeUpdate(sql, columnIndexes)

  override def executeUpdate(sql: String, columnNames: Array[String]): Int = underlying.executeUpdate(sql, columnNames)

  override def execute(sql: String, autoGeneratedKeys: Int): Boolean = underlying.execute(sql, autoGeneratedKeys)

  override def execute(sql: String, columnIndexes: Array[Int]): Boolean = underlying.execute(sql, columnIndexes)

  override def execute(sql: String, columnNames: Array[String]): Boolean = underlying.execute(sql, columnNames)

  override def getResultSetHoldability: Int = underlying.getResultSetHoldability

  override def isClosed: Boolean = underlying.isClosed

  override def setPoolable(poolable: Boolean): Unit = underlying.setPoolable(poolable)

  override def isPoolable: Boolean = underlying.isPoolable

  override def closeOnCompletion(): Unit = underlying.closeOnCompletion()

  override def isCloseOnCompletion: Boolean = underlying.isCloseOnCompletion

  override def unwrap[T](iface: Class[T]): T = underlying.unwrap(iface)

  override def isWrapperFor(iface: Class[_]): Boolean = underlying.isWrapperFor(iface)
}
object TrackedPreparedStatement {
  def make(rawQueryString: String)(underlying: PreparedStatement): TrackedPreparedStatement =
    new TrackedPreparedStatement(underlying, rawQueryString)
}
