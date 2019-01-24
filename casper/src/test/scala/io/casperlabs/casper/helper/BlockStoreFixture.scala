package io.casperlabs.casper.helper

import java.nio.ByteBuffer
import java.nio.file.{Files, Path}

import cats.Id
import io.casperlabs.blockstorage.{BlockStore, LMDBBlockStore}
import org.lmdbjava.{Env, EnvFlags}
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, Suite}
import io.casperlabs.shared.PathOps.RichPath

trait BlockStoreFixture extends BeforeAndAfter { self: Suite =>
  def withStore[R](f: BlockStore[Id] => R): R = {
    val dir   = BlockStoreTestFixture.dbDir
    val store = BlockStoreTestFixture.create(dir)
    try {
      f(store)
    } finally {
      store.close()
      dir.recursivelyDelete()
    }
  }
}

object BlockStoreTestFixture {
  def env(
      path: Path,
      mapSize: Long,
      flags: List[EnvFlags] = List(EnvFlags.MDB_NOTLS)
  ): Env[ByteBuffer] =
    Env
      .create()
      .setMapSize(mapSize)
      .setMaxDbs(8)
      .setMaxReaders(126)
      .open(path.toFile, flags: _*)

  def dbDir: Path   = Files.createTempDirectory("casper-block-store-test-")
  val mapSize: Long = 1024L * 1024L * 100L

  def create(dir: Path): BlockStore[Id] = {
    val environment = env(dir, mapSize)
    val blockStore  = LMDBBlockStore.createWithId(environment, dir)
    blockStore
  }
}

trait BlockStoreTestFixture extends BeforeAndAfterAll { self: Suite =>

  val blockStoreDir = BlockStoreTestFixture.dbDir

  val store = BlockStoreTestFixture.create(blockStoreDir)

  implicit val blockStore = store

  override def afterAll(): Unit = {
    store.close()
    blockStoreDir.recursivelyDelete()
  }
}
