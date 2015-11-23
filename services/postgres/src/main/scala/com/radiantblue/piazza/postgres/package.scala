package com.radiantblue.piazza

import com.radiantblue.piazza.Messages._
import spray.json._

package object postgres {
  final case class KeywordHit(
    name: String,
    checksum: String,
    size: Long,
    locator: String,
    nativeSrid: Option[String],
    latlonBbox: Option[JsValue],
    deployed: Boolean)

  implicit class Queries(val conn: java.sql.Connection) extends AnyVal {
    private def prepare[T](sql: String, generatedKeys: Int = java.sql.Statement.NO_GENERATED_KEYS)(f: java.sql.PreparedStatement => T): T = {
      val pstmt = conn.prepareStatement(sql, generatedKeys)
      try {
        f(pstmt)
      } finally {
        pstmt.close()
      }
    }

    private def iterate[T](statement: java.sql.PreparedStatement)(f: java.sql.ResultSet => T): Vector[T] = {
      val results = statement.executeQuery()
      try {
        Iterator
          .continually(results)
          .takeWhile(_.next)
          .map(f)
          .to[Vector]
      } finally {
        results.close()
      }
    }

    private def iterateKeys[T](statement: java.sql.PreparedStatement)(f: java.sql.ResultSet => T): Vector[T] = {
      statement.execute()
      val results = statement.getGeneratedKeys()
      try {
        Iterator
          .continually(results)
          .takeWhile(_.next)
          .map(f)
          .to[Vector]
      } finally {
        results.close()
      }
    }

    def keywordSearch(keyword: String): Vector[KeywordHit] = {
      val sql = """
      SELECT 
        m.name,
        m.checksum,
        m.size,
        m.locator,
        gm.native_srid,
        ST_AsGeoJson(gm.latlon_bounds),
        (select bool_or(d.state = 'live') from deployments d where d.locator = m.locator)
      FROM metadata m 
      LEFT JOIN geometadata gm USING (locator) 
      WHERE name LIKE ?
      ORDER BY m.id 
      LIMIT 10
      """
      prepare(sql) { ps =>
        ps.setString(1, s"%$keyword%")
        iterate(ps) { rs =>
         KeywordHit(
           name = rs.getString(1),
           checksum = rs.getString(2),
           size = rs.getLong(3),
           locator = rs.getString(4),
           nativeSrid = Option(rs.getString(5)),
           latlonBbox = Option(rs.getString(6)).map(_.parseJson),
           deployed = rs.getBoolean(7))
        }
      }
    }

    def datasetWithMetadata(locator: String): (Metadata, GeoMetadata) = {
      val sql = """
        SELECT 
          m.name,
          m.checksum,
          m.size,
          gm.native_srid,
          ST_XMin(gm.native_bounds),
          ST_XMax(gm.native_bounds),
          ST_YMin(gm.native_bounds),
          ST_YMax(gm.native_bounds),
          ST_XMin(gm.latlon_bounds),
          ST_XMax(gm.latlon_bounds),
          ST_YMin(gm.latlon_bounds),
          ST_YMax(gm.latlon_bounds),
          gm.native_format
        FROM metadata m JOIN geometadata gm USING (locator) 
        WHERE locator = ?
        LIMIT 2
      """
      prepare(sql) { ps =>
        ps.setString(1, locator)
        val result = iterate(ps) { rs =>
          val md = Metadata.newBuilder()
            .setName(rs.getString(1))
            .setChecksum(com.google.protobuf.ByteString.copyFrom(rs.getBytes(2)))
            .setSize(rs.getLong(3))
            .setLocator(locator)
            .build()
          val geo = GeoMetadata.newBuilder()
            .setLocator(locator)
            .setCrsCode(rs.getString(4))
            .setNativeBoundingBox(Messages.GeoMetadata.BoundingBox.newBuilder()
              .setMinX(rs.getDouble(5))
              .setMaxX(rs.getDouble(6))
              .setMinY(rs.getDouble(7))
              .setMaxY(rs.getDouble(8))
              .build())
            .setLatitudeLongitudeBoundingBox(Messages.GeoMetadata.BoundingBox.newBuilder()
              .setMinX(rs.getDouble(9))
              .setMaxX(rs.getDouble(10))
              .setMinY(rs.getDouble(11))
              .setMaxY(rs.getDouble(12))
              .build())
            .setNativeFormat(rs.getString(13))
            .build()
          (md, geo)
        }
        result match {
          case Vector(r) => r
          case Vector() => sys.error(s"No geometadata found for $locator")
          case _ => sys.error(s"Multiple results found for $locator")
        }
      }
    }

    def deploymentWithMetadata(locator: String): Vector[(Metadata, GeoMetadata)] = {
      val sql = """
        SELECT 
          m.name,
          m.checksum,
          m.size,
          gm.native_srid,
          ST_XMin(gm.native_bounds),
          ST_XMax(gm.native_bounds),
          ST_YMin(gm.native_bounds),
          ST_YMax(gm.native_bounds),
          ST_XMin(gm.latlon_bounds),
          ST_XMax(gm.latlon_bounds),
          ST_YMin(gm.latlon_bounds),
          ST_YMax(gm.latlon_bounds),
          gm.native_format
        FROM metadata m 
          JOIN geometadata gm USING (locator)
          JOIN deployments d USING (locator)
        WHERE d.state = 'live'
        AND locator = ?
        LIMIT 1"""
      prepare(sql) { ps =>
        ps.setString(1, locator)
        iterate(ps)(rs => {
            val md = Metadata.newBuilder()
              .setName(rs.getString(1))
              .setChecksum(com.google.protobuf.ByteString.copyFrom(rs.getBytes(2)))
              .setSize(rs.getLong(3))
              .setLocator(locator)
              .build()
            val geo = GeoMetadata.newBuilder()
              .setLocator(locator)
              .setCrsCode(rs.getString(4))
              .setNativeBoundingBox(Messages.GeoMetadata.BoundingBox.newBuilder()
                .setMinX(rs.getDouble(5))
                .setMaxX(rs.getDouble(6))
                .setMinY(rs.getDouble(7))
                .setMaxY(rs.getDouble(8))
                .build())
              .setLatitudeLongitudeBoundingBox(Messages.GeoMetadata.BoundingBox.newBuilder()
                .setMinX(rs.getDouble(9))
                .setMaxX(rs.getDouble(10))
                .setMinY(rs.getDouble(11))
                .setMaxY(rs.getDouble(12))
                .build())
              .setNativeFormat(rs.getString(13))
              .build()
            (md, geo)
        })
      }
    }

    def deployedServers(locator: String): Vector[Server] = {
      val sql = 
        """
        SELECT s.host, s.port, s.local_path
        FROM servers s 
        JOIN deployments d ON (s.id = d.server)
        WHERE d.state = 'live' AND d.locator = ?
        """
      prepare(sql) { ps =>
        ps.setString(1, locator)
        iterate(ps) { rs => Server(rs.getString(1), rs.getString(2), rs.getString(3)) }
      }
    }

    def timedOutServers(): Vector[(Long, String, Server)] = {
      val sql = 
        """
        SELECT * FROM (
          SELECT 
            d.id,
            d.locator,
            s.host,
            s.port,
            s.local_path, 
            (SELECT max(l.lifetime) from leases l where l.deployment = d.id) lifetime 
          FROM deployments d 
          JOIN servers s 
          ON (d.server = s.id) 
          WHERE d.state = 'live')
        results WHERE lifetime < now()
        """
      prepare(sql) { ps =>
        iterate(ps) { rs => (rs.getLong(1), rs.getString(2), Server(rs.getString(3), rs.getInt(4).toString, rs.getString(5))) }
      }
    }

    def startDeployment(locator: String): (Server, Long) = {
      val sql = 
        """
        INSERT INTO deployments (locator, server, state)
        SELECT ?, s.id, 'starting'
        FROM servers s
        ORDER BY response_time
        LIMIT 1
        RETURNING 
          (SELECT host FROM servers WHERE servers.id = deployments.server),
          (SELECT port FROM servers WHERE servers.id = deployments.server),
          (SELECT local_path FROM servers WHERE servers.id = deployments.server),
          id
        """
      prepare(sql) { ps =>
        ps.setString(1, locator)
        iterate(ps)({ rs =>
          (Server(rs.getString(1), rs.getInt(2).toString, rs.getString(3)), rs.getLong(4))
        }).head 
      }
    }

    def completeDeployment(id: Long): Unit = {
      val makeLive = 
        """
        UPDATE deployments 
        SET state = 'live'
        WHERE id = ?
        """
      val setTimeouts = 
        """
        UPDATE leases SET lifetime = now() + '1 hour' WHERE lifetime IS NULL AND deployment = ?
        """
      prepare(makeLive) { ps =>
        ps.setLong(1, id)
        ps.execute()
      }
      prepare(setTimeouts) { ps =>
        ps.setLong(1, id)
        ps.execute()
      }
    }

    def failDeployment(id: Long): Unit = {
      val sql = "UPDATE deployments SET state = 'dead' WHERE id = ?"
      prepare(sql) { ps =>
        ps.setLong(1, id)
        ps.execute()
      }
    }

    def startUndeployment(id: Long): Unit = {
      val sql = "UPDATE deployments SET state = 'killing' WHERE id = ?"
      prepare(sql) { ps =>
        ps.setLong(1, id)
        ps.execute()
      }
    }

    def completeUndeployment(id: Long): Unit = {
      val sql = "UPDATE deployments SET state = 'dead' WHERE id = ?"
      prepare(sql) { ps =>
        ps.setLong(1, id)
        ps.execute()
      }
    }

    def failUndeployment(id: Long): Unit = {
      // maybe just don't worry about the difference between layers we decided
      // to delete and layers we successfully deleted?

      // val sql = "DELETE FROM deployments WHERE id = ?"
      // prepare(sql) { ps =>
      //   ps.setLong(1, id)
      //   ps.execute()
      // }
    }

    def getDeploymentStatus(locator: String): DeployStatus = {
      val sql =
        """
        SELECT d.state, d.id, s.host, s.port, s.local_path
        FROM deployments d
        JOIN servers s ON (d.server = s.id)
        WHERE d.locator = ?
        """
      prepare(sql) { ps =>
        ps.setString(1, locator)
        iterate(ps)({rs =>
          val state = rs.getString(1)
          val id = rs.getInt(2)

          state match {
            case "starting" =>
              Starting(id)
            case "live" => 
              val server = Server(rs.getString(3), rs.getInt(4).toString, rs.getString(5))
              Live(id, server)
            case "killing" => Killing
            case "dead" => Dead
          }
        }).headOption.getOrElse(Dead)
      }
    }

    def insertMetadata(md: Metadata): Unit = {
      val sql = "INSERT INTO metadata (name, locator, checksum, size) VALUES (?, ?, ?, ?)"
      prepare(sql) { ps =>
        ps.setString(1, md.getName)
        ps.setString(2, md.getLocator)
        ps.setString(3, md.getChecksum.toByteArray.map(b => f"$b%02x").mkString)
        ps.setLong(4, md.getSize)
        ps.executeUpdate()
      }
    }

    def insertGeoMetadata(g: GeoMetadata): Unit = {
      val sql = ("""
      INSERT INTO geometadata (locator, native_srid, native_bounds, latlon_bounds, native_format) VALUES ( 
        ?,
        ?, 
        ST_MakeBox2D(ST_Point(?, ?), ST_Point(?, ?)),
        ST_MakeBox2D(ST_Point(?, ?), ST_Point(?, ?)),
        ?
      ) """)
      prepare(sql) { ps =>
        ps.setString(1, g.getLocator)
        ps.setString(2, g.getCrsCode)
        ps.setDouble(3, g.getNativeBoundingBox.getMinX)
        ps.setDouble(4, g.getNativeBoundingBox.getMinY)
        ps.setDouble(5, g.getNativeBoundingBox.getMaxX)
        ps.setDouble(6, g.getNativeBoundingBox.getMaxY)
        ps.setDouble(7, g.getLatitudeLongitudeBoundingBox.getMinX)
        ps.setDouble(8, g.getLatitudeLongitudeBoundingBox.getMinY)
        ps.setDouble(9, g.getLatitudeLongitudeBoundingBox.getMaxX)
        ps.setDouble(10, g.getLatitudeLongitudeBoundingBox.getMaxY)
        ps.setString(11, g.getNativeFormat)
        ps.executeUpdate()
      }
    }

    def attachLease(locator: String, deployment: Long, tag: Array[Byte]): Lease = {
      val timeToLive = "1 hour";
      val sql = 
        """
        INSERT INTO leases (locator, deployment, lifetime, tag) VALUES (?, ?, now() + (? :: INTERVAL), ?) RETURNING id, lifetime
        """
      prepare(sql) { ps =>
        ps.setString(1, locator)
        ps.setLong(2, deployment)
        ps.setString(3, timeToLive)
        ps.setBytes(4, tag)
        iterate(ps)({ rs => Lease(rs.getLong(1), deployment, Some(rs.getTimestamp(2)), tag) }).head
      }
    }

    def createLease(locator: String, deployment: Long, tag: Array[Byte]): Lease = {
      val sql = 
        """
        INSERT INTO leases (locator, deployment, lifetime, tag) VALUES (?, ?, NULL, ?) RETURNING id
        """
      prepare(sql) { ps =>
        ps.setString(1, locator)
        ps.setLong(2, deployment)
        ps.setBytes(3, tag)
        iterate(ps)({ rs => Lease(rs.getLong(1), deployment, None, tag) }).head
      }
    }

    def setLeaseTime(id: Long, timeToLive: String): Unit = {
      val sql =
        """
        UPDATE leases SET lifetime = now() + (? :: INTERVAL) WHERE id = ?
        """
      prepare(sql) { ps =>
        ps.setString(1, timeToLive)
        ps.setLong(2, id)
        ps.execute()
      }
    }

    def getLeaseById(id: Int): (String, java.sql.Timestamp, Server) = {
      val sql = 
        """
        SELECT l.locator, l.lifetime, s.host, s.port, s.local_path
        FROM leases l
        JOIN deployments d ON (l.deployment = d.id)
        JOIN servers s ON (d.server = s.id)
        WHERE l.id = id
        """
      prepare(sql) { ps =>
        ps.setInt(1, id)
        iterate(ps)({ rs =>
          val locator = rs.getString(1)
          val lifetime = rs.getTimestamp(2)
          val server = Server(rs.getString(3), rs.getInt(4).toString, rs.getString(5))
          (locator, lifetime, server)
        }).head
      }
    }

    def checkLeaseDeployment(id: Long): DeployStatus = {
      val sql = 
        """
        SELECT d.state, d.id, s.host, s.port, s.local_path
        FROM leases l
        JOIN deployments d ON (l.deployment = d.id)
        JOIN servers s ON (d.server = s.id)
        WHERE l.id = ?
        """
      prepare(sql) { ps =>
        ps.setLong(1, id)
        iterate(ps)({ rs =>
          val state = rs.getString(1)
          val id = rs.getInt(2)

          state match {
            case "starting" => 
              Starting(id)
            case "live" =>
              val server = Server(rs.getString(3), rs.getInt(4).toString, rs.getString(5))
              Live(id, server)
            case "killing" => Killing
            case "dead" => Dead
          }
        }).head
      }
    }

    def getLeasesByDeployment(id: Long): Vector[Lease] = {
      val sql = """ SELECT l.id, l.deployment, l.lifetime, l.tag FROM leases l WHERE l.deployment = ? """
      prepare(sql) { ps =>
        ps.setLong(1, id)
        iterate(ps) { rs =>
          Lease(rs.getLong(1), rs.getLong(2), Option(rs.getTimestamp(3)), rs.getBytes(4))
        }
      }
    }
  }
}
