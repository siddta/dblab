package ch.epfl.data
package legobase
package optimization

import scala.language.implicitConversions
import pardis.ir._
import reflect.runtime.universe.{ TypeTag, Type }
import pardis.optimization._
import deep._
import pardis.types._
import pardis.types.PardisTypeImplicits._
import pardis.shallow.utils.DefaultValue

class HashMapPartitioningTransformer(override val IR: LoweringLegoBase) extends RuleBasedTransformer[LoweringLegoBase](IR) with StructCollector[LoweringLegoBase] {
  import IR._
  val allMaps = scala.collection.mutable.Set[Rep[Any]]()
  val partitionedMaps = scala.collection.mutable.Set[Rep[Any]]()
  val leftPartFunc = scala.collection.mutable.Map[Rep[Any], String]()
  val rightPartFunc = scala.collection.mutable.Map[Rep[Any], String]()
  val leftPartArr = scala.collection.mutable.Map[Rep[Any], Rep[Array[Any]]]()
  val rightPartArr = scala.collection.mutable.Map[Rep[Any], Rep[Array[Any]]]()
  val leftLoopSymbol = scala.collection.mutable.Map[Rep[Any], While]()
  val rightLoopSymbol = scala.collection.mutable.Map[Rep[Any], While]()
  // TODO when WindowOp and LeftHashSemiJoin and HashJoinAnti are supported will be removed
  val notSupportedMaps = scala.collection.mutable.Set[Rep[Any]]()

  var transformedMapsCount = 0

  case class HashMapPartitionObject(mapSymbol: Rep[Any], left: Option[PartitionObject], right: Option[PartitionObject]) {
    def partitionedObject: PartitionObject = (left, right) match {
      case (Some(v), _)    => v
      case (None, Some(v)) => v
      case _               => throw new Exception(s"$this doesn't have partitioned object")
    }
    def hasLeft: Boolean = left.nonEmpty
  }
  case class PartitionObject(arr: Rep[Array[Any]], fieldFunc: String, loopSymbol: While) {
    def buckets = numBuckets(this)
    def count = partitionedObjectsCount(this)
    def parArr = partitionedObjectsArray(this).asInstanceOf[Rep[Array[Array[Any]]]]
  }
  val partitionedHashMapObjects = scala.collection.mutable.Set[HashMapPartitionObject]()
  val partitionedObjectsArray = scala.collection.mutable.Map[PartitionObject, Rep[Array[Any]]]()
  val partitionedObjectsCount = scala.collection.mutable.Map[PartitionObject, Rep[Array[Int]]]()

  // TODO should be `|| ?.right.nonEmpty`
  def shouldBePartitioned[T: TypeRep](hm: Rep[T]): Boolean = partitionedMaps.exists(x => x == hm) && !notSupportedMaps.exists(x => x == hm) && (getPartitionedObject(hm).left.nonEmpty || getPartitionedObject(hm).right.nonEmpty)
  def getPartitionedObject[T: TypeRep](hm: Rep[T]): HashMapPartitionObject = partitionedHashMapObjects.find(x => x.mapSymbol == hm).get

  override def optimize[T: TypeRep](node: Block[T]): Block[T] = {
    // phase = FindLoopLength
    // traverseBlock(node)
    // phase = FindColumnArray
    // traverseBlock(node)
    // // System.out.println(s"columnStorePartitionedSymbols: $columnStorePartitionedSymbols, partitionedArrayLengthSymbols: $partitionedArrayLengthSymbols")
    // phase = FindPartitionedLoop
    traverseBlock(node)
    // System.out.println(s"leftPartFunc: $leftPartFunc")
    // System.out.println(s"rightPartFunc: $rightPartFunc")
    // System.out.println(s"leftPartArr: $leftPartArr")
    // System.out.println(s"rightPartArr: $rightPartArr")
    val supportedMaps = partitionedMaps diff notSupportedMaps
    partitionedHashMapObjects ++= partitionedMaps.map({ hm =>
      val left = leftPartArr.get(hm).map(v => PartitionObject(v, leftPartFunc(hm), leftLoopSymbol(hm)))
      val right = rightPartArr.get(hm).map(v => PartitionObject(v, rightPartFunc(hm), rightLoopSymbol(hm)))
      HashMapPartitionObject(hm, left, right)
    })
    // System.out.println(s"partitionedHashMapObjects: $partitionedHashMapObjects")
    // System.out.println(s"notSupportedMaps: $notSupportedMaps")
    // System.out.println(s"supportedMaps: $supportedMaps")

    val res = transformProgram(node)
    System.out.println(s"[${scala.Console.BLUE}$transformedMapsCount${scala.Console.BLACK}] MultiMaps partitioned!")
    res
  }

  analysis += statement {
    case sym -> (node @ MultiMapNew()) if node.typeB.isRecord =>
      allMaps += sym
      ()
  }

  analysis += rule {
    case node @ MultiMapAddBinding(nodeself, Def(StructImmutableField(struct, field)), nodev) if allMaps.contains(nodeself) =>
      partitionedMaps += nodeself
      leftPartFunc += nodeself -> field
      leftLoopSymbol += nodeself -> currentLoopSymbol
      struct match {
        case Def(ArrayApply(arr, ind)) => leftPartArr += nodeself -> arr
        case _                         =>
      }
      ()
  }

  analysis += rule {
    case node @ MultiMapGet(nodeself, Def(StructImmutableField(struct, field))) if allMaps.contains(nodeself) =>
      partitionedMaps += nodeself
      rightPartFunc += nodeself -> field
      rightLoopSymbol += nodeself -> currentLoopSymbol
      struct match {
        case Def(ArrayApply(arr, ind)) => rightPartArr += nodeself -> arr
        case _                         =>
      }
      ()
  }

  // TODO when WindowOp, HashJoinAnti are supported, this case should be removed
  analysis += rule {
    case node @ MultiMapForeach(nodeself, _) if allMaps.contains(nodeself) =>
      notSupportedMaps += nodeself
      ()
  }

  var currentLoopSymbol: While = _

  analysis += statement {
    case sym -> (node @ While(_, _)) =>
      currentLoopSymbol = node
  }

  def numBuckets(partitionedObject: PartitionObject): Rep[Int] = partitionedObject.arr match {
    case Def(ArrayNew(l)) => l / unit(4)
    case sym              => throw new Exception(s"setting default value for $sym")
  }
  def bucketSize(partitionedObject: PartitionObject): Rep[Int] = //unit(100)
    // numBuckets(partitionedObject)
    unit(1 << 10)

  // def numBuckets(partitionedObject: PartitionObject): Rep[Int] = unit(1 << 9)
  // def bucketSize(partitionedObject: PartitionObject): Rep[Int] = partitionedObject.arr match {
  //   case Def(ArrayNew(l)) => l / unit(4)
  //   case sym              => System.out.println(s"setting default value for $sym"); numBuckets(sym.tp.typeArguments(0))
  // }
  def recreateNode[T: TypeRep](exp: Rep[T]): Rep[T] = exp match {
    case Def(node) => toAtom(node)(exp.tp)
    case _         => ???
  }

  def array_foreach[T: TypeRep](arr: Rep[Array[T]], f: Rep[T] => Rep[Unit]): Rep[Unit] = {
    Range(unit(0), arr.length).foreach {
      __lambda { i =>
        val e = arr(i)
        f(e)
      }
    }
  }

  def createPartitionArray(partitionedObject: PartitionObject): Unit = {
    class InnerType
    implicit val typeInner = partitionedObject.arr.tp.typeArguments(0).asInstanceOf[TypeRep[InnerType]]
    val buckets = numBuckets(partitionedObject)
    val originalArray = {
      val origArray =
        if (!seenArrays.contains(partitionedObject.arr)) {
          seenArrays += partitionedObject.arr
          recreateNode(partitionedObject.arr)
        } else {
          partitionedObject.arr
        }
      origArray.asInstanceOf[Rep[Array[InnerType]]]
    }
    val partitionedArray = __newArray[Array[InnerType]](buckets)
    val partitionedCount = __newArray[Int](buckets)
    partitionedObjectsArray += partitionedObject -> partitionedArray.asInstanceOf[Rep[Array[Any]]]
    partitionedObjectsCount += partitionedObject -> partitionedCount
    Range(unit(0), buckets).foreach {
      __lambda { i =>
        partitionedArray(i) = __newArray[InnerType](bucketSize(partitionedObject))
      }
    }
    val index = __newVarNamed[Int](unit(0), "partIndex")
    array_foreach(originalArray, {
      (e: Rep[InnerType]) =>
        // TODO needs a better way of computing the index of each object
        val pkey = field[Int](e, partitionedObject.fieldFunc) % buckets
        val currIndex = partitionedCount(pkey)
        val partitionedArrayBucket = partitionedArray(pkey)
        partitionedArrayBucket(currIndex) = e
        partitionedCount(pkey) = currIndex + unit(1)
        __assign(index, readVar(index) + unit(1))
    })

  }

  val seenArrays = scala.collection.mutable.Set[Rep[Any]]()

  // rewrite += removeStatement {
  //   case sym -> ArrayNew(size) if seenArrays.contains(sym) =>
  //     ()
  // }

  rewrite += statement {
    case sym -> (node @ ArrayNew(size)) if !seenArrays.contains(sym) =>
      seenArrays += sym
      reflectStm(Stm(sym, node))
      sym
  }

  rewrite += statement {
    case sym -> (node @ MultiMapNew()) if shouldBePartitioned(sym) => {
      if (shouldBePartitioned(sym)) {
        val hmParObj = getPartitionedObject(sym)(sym.tp)
        // hmParObj.left.foreach { l =>
        //   createPartitionArray(l)
        // }
        // hmParObj.right.foreach { r =>
        //   createPartitionArray(r)
        // }
        System.out.println(hmParObj.partitionedObject.arr.tp.typeArguments(0) + " Partitioned")

        createPartitionArray(hmParObj.partitionedObject)

        sym
      } else {
        recreateNode(node)
      }
    }
  }

  rewrite += remove {
    case MultiMapGet(mm, elem) if shouldBePartitioned(mm) => {
      ()
    }
  }

  rewrite += remove {
    case MultiMapAddBinding(mm, _, nodev) if shouldBePartitioned(mm) && getPartitionedObject(mm).hasLeft && fillingHole.get(mm).isEmpty =>
      ()
  }

  rewrite += rule {
    case MultiMapAddBinding(mm, _, nodev) if shouldBePartitioned(mm) && getPartitionedObject(mm).hasLeft && fillingHole(mm) =>
      // System.out.println(s"came here! for $mm")
      fillingFunction(mm)()
  }

  rewrite += rule {
    case MultiMapAddBinding(mm, elem, nodev) if shouldBePartitioned(mm) && !getPartitionedObject(mm).hasLeft =>
      val hmParObj = getPartitionedObject(mm)
      val leftArray = hmParObj.partitionedObject
      val key = elem.asInstanceOf[Rep[Int]] % leftArray.buckets
      val whileLoop = leftArray.loopSymbol
      // System.out.println(s"loop: ${leftArray.loopSymbol}")

      val res = Range(unit(0), leftArray.count(key)).foreach {
        __lambda { i =>
          class InnerType
          implicit val typeInner = leftArray.parArr.tp.typeArguments(0).typeArguments(0).asInstanceOf[TypeRep[InnerType]]
          val leftParArray = leftArray.parArr.asInstanceOf[Rep[Array[Array[InnerType]]]]
          val e = leftParArray(key)(i)
          fillingElem(mm) = e
          fillingFunction(mm) = () => nodev
          // fillingFunction(mm) = () => {
          //   __ifThenElse(field[Int](e, leftArray.fieldFunc) __== elem, {
          //     inlineFunction(f, e)
          //   }, {
          //     unit(())
          //   })
          // }
          fillingHole(mm) = true
          // System.out.println(s"inlining block ${whileLoop.body}")
          val res1 = inlineBlock2(whileLoop.body)
          // System.out.println(s"inlining block done")
          // System.out.println(s"fillingHole $fillingHole")
          fillingHole.remove(mm)
          transformedMapsCount += 1
          // System.out.println(s"fillingHole $fillingHole")
          res1
        }
      }
      // System.out.println(s"foreach done $res")

      res
  }

  rewrite += rule {
    case ArrayApply(arr, _) if {
      partitionedHashMapObjects.find(obj => shouldBePartitioned(obj.mapSymbol) /* && obj.hasLeft */ && obj.partitionedObject.arr == arr) match {
        case Some(obj) => fillingHole.get(obj.mapSymbol).nonEmpty
        case _         => false
      }
    } =>
      fillingElem(partitionedHashMapObjects.find(obj => shouldBePartitioned(obj.mapSymbol) /* && obj.hasLeft*/ && obj.partitionedObject.arr == arr).get.mapSymbol)
  }

  rewrite += remove {
    case node @ While(_, _) if {
      partitionedHashMapObjects.find(obj => shouldBePartitioned(obj.mapSymbol) && obj.partitionedObject.loopSymbol == node) match {
        case Some(obj) => true
        case _         => false
      }
    } =>
      ()
  }

  rewrite += rule {
    case OptionNonEmpty(Def(MultiMapGet(mm, elem))) if shouldBePartitioned(mm) => {
      unit(true)
    }
  }

  rewrite += remove {
    case OptionGet(Def(MultiMapGet(mm, elem))) if shouldBePartitioned(mm) => {
      ()
    }
  }

  val fillingHole = scala.collection.mutable.Map[Rep[Any], Boolean]()
  var fillingFunction = scala.collection.mutable.Map[Rep[Any], () => Rep[Any]]()
  var fillingElem = scala.collection.mutable.Map[Rep[Any], Rep[Any]]()

  rewrite += rule {
    case SetForeach(Def(OptionGet(Def(MultiMapGet(mm, elem)))), f) if shouldBePartitioned(mm) && getPartitionedObject(mm).hasLeft => {
      val hmParObj = getPartitionedObject(mm)
      val leftArray = hmParObj.partitionedObject
      val key = elem.asInstanceOf[Rep[Int]] % leftArray.buckets
      val whileLoop = leftArray.loopSymbol
      // System.out.println(s"loop: ${leftArray.loopSymbol}")

      val res = Range(unit(0), leftArray.count(key)).foreach {
        __lambda { i =>
          class InnerType
          implicit val typeInner = leftArray.parArr.tp.typeArguments(0).typeArguments(0).asInstanceOf[TypeRep[InnerType]]
          val leftParArray = leftArray.parArr.asInstanceOf[Rep[Array[Array[InnerType]]]]
          val e = leftParArray(key)(i)
          fillingElem(mm) = e
          fillingFunction(mm) = () => {
            __ifThenElse[Unit](field[Int](e, leftArray.fieldFunc) __== elem, {
              inlineFunction(f.asInstanceOf[Rep[InnerType => Unit]], e)
            }, {
              unit(())
            })
          }
          fillingHole(mm) = true
          // System.out.println(s"inlining block ${whileLoop.body}")
          val res1 = inlineBlock2(whileLoop.body)
          // System.out.println(s"inlining block done")
          // System.out.println(s"fillingHole $fillingHole")
          fillingHole.remove(mm)
          transformedMapsCount += 1
          // System.out.println(s"fillingHole $fillingHole")
          res1
        }
      }
      // System.out.println(s"foreach done $res")

      res
    }
  }

  rewrite += rule {
    case SetExists(Def(OptionGet(Def(MultiMapGet(mm, elem)))), p) if shouldBePartitioned(mm) && getPartitionedObject(mm).hasLeft => {
      val hmParObj = getPartitionedObject(mm)
      val leftArray = hmParObj.partitionedObject
      val key = elem.asInstanceOf[Rep[Int]] % leftArray.buckets
      val whileLoop = leftArray.loopSymbol
      // System.out.println(s"loop: ${leftArray.loopSymbol}")

      val result = __newVarNamed[Boolean](unit(false), "existsResult")
      Range(unit(0), leftArray.count(key)).foreach {
        __lambda { i =>
          class InnerType
          implicit val typeInner = leftArray.parArr.tp.typeArguments(0).typeArguments(0).asInstanceOf[TypeRep[InnerType]]
          val leftParArray = leftArray.parArr.asInstanceOf[Rep[Array[Array[InnerType]]]]
          val e = leftParArray(key)(i)
          fillingElem(mm) = e
          fillingFunction(mm) = () => {
            __ifThenElse[Unit]((field[Int](e, leftArray.fieldFunc) __== elem) && inlineFunction(p, e), {
              __assign(result, unit(true))
            }, {
              unit(())
            })
          }
          fillingHole(mm) = true
          // System.out.println(s"inlining block ${whileLoop.body}")
          val res1 = inlineBlock2(whileLoop.body)
          // System.out.println(s"inlining block done")
          // System.out.println(s"fillingHole $fillingHole")
          fillingHole.remove(mm)
          transformedMapsCount += 1
          // System.out.println(s"fillingHole $fillingHole")
          res1
        }
      }
      // System.out.println(s"foreach done $res")

      readVar(result)
    }
  }

  rewrite += remove {
    case SetForeach(Def(OptionGet(Def(MultiMapGet(mm, elem)))), f) if shouldBePartitioned(mm) && !getPartitionedObject(mm).hasLeft && fillingHole.get(mm).isEmpty =>
      ()
  }

  rewrite += rule {
    case SetForeach(Def(OptionGet(Def(MultiMapGet(mm, elem)))), f) if shouldBePartitioned(mm) && !getPartitionedObject(mm).hasLeft && fillingHole(mm) =>
      inlineFunction(f, fillingFunction(mm)())
  }
}
