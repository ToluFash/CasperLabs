package io.casperlabs.blockstorage

import cats._
import cats.effect.Sync
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.BlockStore.BlockHash
import io.casperlabs.blockstorage.InMemBlockStore.emptyMapRef
import io.casperlabs.casper.protocol.{BlockMessage, Header}
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.MetricsNOP
import io.casperlabs.blockstorage.blockImplicits.{blockBatchesGen, blockElementsGen}
import io.casperlabs.shared.Log
import io.casperlabs.shared.PathOps._
import io.casperlabs.storage.BlockMsgWithTransform
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.Scheduler.Implicits.global
import org.scalactic.anyvals.PosInt
import org.scalatest._
import org.scalatest.prop.GeneratorDrivenPropertyChecks

import scala.language.higherKinds

trait BlockStoreTest
    extends FlatSpecLike
    with Matchers
    with OptionValues
    with EitherValues
    with GeneratorDrivenPropertyChecks
    with BeforeAndAfterAll {

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = PosInt(100))

  private[this] def toBlockMessage(bh: BlockHash, v: Long, ts: Long): BlockMessage =
    BlockMessage(blockHash = bh)
      .withHeader(Header().withProtocolVersion(v).withTimestamp(ts))

  def withStore[R](f: BlockStore[Task] => Task[R]): R

  "Block Store" should "return Some(message) on get for a published key" in {
    forAll(blockElementsGen, minSize(0), sizeRange(10)) { blockStoreElements =>
      withStore { store =>
        val items = blockStoreElements
        for {
          _ <- items.traverse_(store.put)
          _ <- items.traverse[Task, Assertion] { block =>
                store.get(block.getBlockMessage.blockHash).map(_ shouldBe Some(block))
              }
          result <- store.find(_ => true).map(_.size shouldEqual items.size)
        } yield result
      }
    }
  }

  it should "discover keys by predicate" in {
    forAll(blockElementsGen, minSize(0), sizeRange(10)) { blockStoreElements =>
      withStore { store =>
        val items = blockStoreElements
        for {
          _ <- items.traverse_(store.put)
          _ <- items.traverse[Task, Assertion] { block =>
                store
                  .find(_ == ByteString.copyFrom(block.getBlockMessage.blockHash.toByteArray))
                  .map { w =>
                    w should have size 1
                    w.head._2 shouldBe block
                  }
              }
          result <- store.find(_ => true).map(_.size shouldEqual items.size)
        } yield result
      }
    }
  }

  it should "overwrite existing value" in
    forAll(blockElementsGen, minSize(0), sizeRange(10)) { blockStoreElements =>
      withStore { store =>
        val items = blockStoreElements.map {
          case BlockMsgWithTransform(Some(block), transform) =>
            val newBlock = toBlockMessage(block.blockHash, 200L, 20000L)
            (
              block.blockHash,
              BlockMsgWithTransform(Some(block), transform),
              BlockMsgWithTransform(Some(newBlock), transform)
            )
        }
        for {
          _ <- items.traverse_[Task, Unit] { case (k, v1, _) => store.put(k, v1) }
          _ <- items.traverse_[Task, Assertion] {
                case (k, v1, _) => store.get(k).map(_ shouldBe Some(v1))
              }
          _ <- items.traverse_[Task, Unit] { case (k, _, v2) => store.put(k, v2) }
          _ <- items.traverse_[Task, Assertion] {
                case (k, _, v2) => store.get(k).map(_ shouldBe Some(v2))
              }
          result <- store.find(_ => true).map(_.size shouldEqual items.size)
        } yield result
      }
    }

  it should "rollback the transaction on error" in {
    withStore { store =>
      val exception = new RuntimeException("msg")

      def elem: (BlockHash, BlockMsgWithTransform) =
        throw exception

      for {
        _          <- store.find(_ => true).map(_.size shouldEqual 0)
        putAttempt <- store.put { elem }.attempt
        _          = putAttempt.left.value shouldBe exception
        result     <- store.find(_ => true).map(_.size shouldEqual 0)
      } yield result
    }
  }
}

class InMemBlockStoreTest extends BlockStoreTest {
  override def withStore[R](f: BlockStore[Task] => Task[R]): R = {
    val test = for {
      refTask <- emptyMapRef[Task]
      metrics = new MetricsNOP[Task]()
      store   = InMemBlockStore.create[Task](Monad[Task], refTask, metrics)
      _       <- store.find(_ => true).map(map => assert(map.isEmpty))
      result  <- f(store)
    } yield result
    test.unsafeRunSync
  }
}

class LMDBBlockStoreTest extends BlockStoreTest {

  import java.nio.file.{Files, Path}

  private[this] def mkTmpDir(): Path = Files.createTempDirectory("block-store-test-")
  private[this] val mapSize: Long    = 100L * 1024L * 1024L * 4096L

  override def withStore[R](f: BlockStore[Task] => Task[R]): R = {
    val dbDir                           = mkTmpDir()
    val env                             = Context.env(dbDir, mapSize)
    implicit val metrics: Metrics[Task] = new MetricsNOP[Task]()
    val store                           = LMDBBlockStore.create[Task](env, dbDir)
    val test = for {
      _      <- store.find(_ => true).map(map => assert(map.isEmpty))
      result <- f(store)
    } yield result
    try {
      test.unsafeRunSync
    } finally {
      env.close()
      dbDir.recursivelyDelete()
    }
  }
}

class FileLMDBIndexBlockStoreTest extends BlockStoreTest {
  val scheduler = Scheduler.fixedPool("block-storage-test-scheduler", 4)

  import java.nio.file.{Files, Path}

  private[this] def mkTmpDir(): Path = Files.createTempDirectory("block-store-test-")
  private[this] val mapSize: Long    = 100L * 1024L * 1024L * 4096L

  override def withStore[R](f: BlockStore[Task] => Task[R]): R = {
    val dbDir                           = mkTmpDir()
    implicit val metrics: Metrics[Task] = new MetricsNOP[Task]()
    implicit val log: Log[Task]         = new Log.NOPLog[Task]()
    val env                             = Context.env(dbDir, mapSize)
    val test = for {
      store  <- FileLMDBIndexBlockStore.create[Task](env, dbDir).map(_.right.get)
      _      <- store.find(_ => true).map(map => assert(map.isEmpty))
      result <- f(store)
    } yield result
    try {
      test.unsafeRunSync
    } finally {
      env.close()
      dbDir.recursivelyDelete()
    }
  }

  private def createBlockStore(blockStoreDataDir: Path): Task[BlockStore[Task]] = {
    implicit val metrics = new MetricsNOP[Task]()
    implicit val log     = new Log.NOPLog[Task]()
    val env              = Context.env(blockStoreDataDir, 100L * 1024L * 1024L * 4096L)
    FileLMDBIndexBlockStore.create[Task](env, blockStoreDataDir).map(_.right.get)
  }

  def withStoreLocation[R](f: Path => Task[R]): R = {
    val testProgram = Sync[Task].bracket {
      Sync[Task].delay {
        mkTmpDir()
      }
    } { blockStoreDataDir =>
      f(blockStoreDataDir)
    } { blockStoreDataDir =>
      Sync[Task].delay {
        blockStoreDataDir.recursivelyDelete()
      }
    }
    testProgram.unsafeRunSync(scheduler)
  }

  "FileLMDBIndexBlockStore" should "persist storage on restart" in {
    forAll(blockElementsGen, minSize(0), sizeRange(10)) { blockStoreElements =>
      withStoreLocation { blockStoreDataDir =>
        for {
          firstStore  <- createBlockStore(blockStoreDataDir)
          _           <- blockStoreElements.traverse_[Task, Unit](firstStore.put)
          _           <- firstStore.close()
          secondStore <- createBlockStore(blockStoreDataDir)
          _ <- blockStoreElements.traverse[Task, Assertion] {
                case b @ BlockMsgWithTransform(Some(block), _) =>
                  secondStore.get(block.blockHash).map(_ shouldBe Some(b))
              }
          result <- secondStore.find(_ => true).map(_.size shouldEqual blockStoreElements.size)
          _      <- secondStore.close()
        } yield result
      }
    }
  }

  it should "persist storage after checkpoint" in {
    forAll(blockElementsGen, minSize(10), sizeRange(10)) { blockStoreElements =>
      withStoreLocation { blockStoreDataDir =>
        val (firstHalf, secondHalf) = blockStoreElements.splitAt(blockStoreElements.size / 2)
        for {
          firstStore <- createBlockStore(blockStoreDataDir)
          _          <- firstHalf.traverse_[Task, Unit](firstStore.put)
          _          <- firstStore.checkpoint()
          _          <- secondHalf.traverse_[Task, Unit](firstStore.put)
          _ <- blockStoreElements.traverse[Task, Assertion] {
                case b @ BlockMsgWithTransform(Some(block), _) =>
                  firstStore.get(block.blockHash).map(_ shouldBe Some(b))
              }
          _           <- firstStore.find(_ => true).map(_.size shouldEqual blockStoreElements.size)
          _           <- firstStore.close()
          secondStore <- createBlockStore(blockStoreDataDir)
          _ <- blockStoreElements.traverse[Task, Assertion] {
                case b @ BlockMsgWithTransform(Some(block), _) =>
                  secondStore.get(block.blockHash).map(_ shouldBe Some(b))
              }
          result <- secondStore.find(_ => true).map(_.size shouldEqual blockStoreElements.size)
          _      <- secondStore.close()
        } yield result
      }
    }
  }

  it should "be able to store multiple checkpoints" in {
    forAll(blockBatchesGen, minSize(5), sizeRange(10)) { blockStoreBatches =>
      withStoreLocation { blockStoreDataDir =>
        val blocks = blockStoreBatches.flatten
        for {
          firstStore <- createBlockStore(blockStoreDataDir)
          _ <- blockStoreBatches.traverse_[Task, Unit](
                blockStoreElements =>
                  blockStoreElements
                    .traverse_[Task, Unit](firstStore.put) *> firstStore.checkpoint()
              )
          _ <- blocks.traverse[Task, Assertion] {
                case b @ BlockMsgWithTransform(Some(block), _) =>
                  firstStore.get(block.blockHash).map(_ shouldBe Some(b))
              }
          _           <- firstStore.find(_ => true).map(_.size shouldEqual blocks.size)
          _           <- firstStore.close()
          secondStore <- createBlockStore(blockStoreDataDir)
          _ <- blocks.traverse[Task, Assertion] {
                case b @ BlockMsgWithTransform(Some(block), _) =>
                  secondStore.get(block.blockHash).map(_ shouldBe Some(b))
              }
          result <- secondStore.find(_ => true).map(_.size shouldEqual blocks.size)
          _      <- secondStore.close()
        } yield result
      }
    }
  }

  it should "be able to clean storage and continue to work" in {
    forAll(blockBatchesGen, minSize(5), sizeRange(10)) { blockStoreBatches =>
      withStoreLocation { blockStoreDataDir =>
        val blocks         = blockStoreBatches.flatten
        val checkpointsDir = blockStoreDataDir.resolve("checkpoints")
        for {
          firstStore <- createBlockStore(blockStoreDataDir)
          _ <- blockStoreBatches.traverse_[Task, Unit](
                blockStoreElements =>
                  blockStoreElements
                    .traverse_[Task, Unit](firstStore.put) *> firstStore.checkpoint()
              )
          _ = checkpointsDir.toFile.list().size shouldBe blockStoreBatches.size
          _ <- blocks.traverse[Task, Assertion] {
                case b @ BlockMsgWithTransform(Some(block), _) =>
                  firstStore.get(block.blockHash).map(_ shouldBe Some(b))
              }
          _ <- firstStore.find(_ => true).map(_.size shouldEqual blocks.size)
          _ <- firstStore.clear()
          _ = checkpointsDir.toFile.list().size shouldBe 0
          _ <- firstStore.find(_ => true).map(_.size shouldEqual 0)
          _ <- blockStoreBatches.traverse_[Task, Unit](
                blockStoreElements =>
                  blockStoreElements
                    .traverse_[Task, Unit](firstStore.put) *> firstStore.checkpoint()
              )
          _           = checkpointsDir.toFile.list().size shouldBe blockStoreBatches.size
          _           <- firstStore.close()
          secondStore <- createBlockStore(blockStoreDataDir)
          _ <- blocks.traverse[Task, Assertion] {
                case b @ BlockMsgWithTransform(Some(block), _) =>
                  secondStore.get(block.blockHash).map(_ shouldBe Some(b))
              }
          result <- secondStore.find(_ => true).map(_.size shouldEqual blocks.size)
        } yield result
      }
    }
  }
}
