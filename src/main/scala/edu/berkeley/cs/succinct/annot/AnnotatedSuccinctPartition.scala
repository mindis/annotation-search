package org.apache.spark.succinct.annot

import java.io.{ObjectInputStream, ObjectOutputStream}
import java.util.NoSuchElementException

import edu.berkeley.cs.succinct.SuccinctIndexedFile
import edu.berkeley.cs.succinct.annot._
import edu.berkeley.cs.succinct.buffers.SuccinctIndexedFileBuffer
import edu.berkeley.cs.succinct.buffers.annot._
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.util.{KnownSizeEstimation, SizeEstimator}

import scala.collection.JavaConverters._
import scala.io.Source

class AnnotatedSuccinctPartition(val keys: Array[String], val documentBuffer: SuccinctIndexedFile,
                                 val annotationBuffer: SuccinctAnnotationBuffer,
                                 val annotBufferMap: Map[String, (Int, Int)])
  extends KnownSizeEstimation with Serializable {

  /**
    * Get an [[Iterator]] over the documents.
    *
    * @return An [[Iterator]] over the documents.
    */
  def iterator: Iterator[(String, String)] = {
    new Iterator[(String, String)] {
      var currentRecordId = 0

      override def hasNext: Boolean = currentRecordId < keys.length

      override def next(): (String, String) = {
        val curKey = keys(currentRecordId)
        val curVal = new String(documentBuffer.getRecord(currentRecordId))
        currentRecordId += 1
        (curKey, curVal)
      }
    }
  }

  /**
    * Saves the partition at the specified location prefix.
    *
    * @param location The prefix for the partition's save location.
    */
  def save(location: String, conf: Configuration): Unit = {
    val pathDoc = new Path(location + ".sdocs")
    val pathDocIds = new Path(location + ".sdocids")

    val fs = FileSystem.get(pathDoc.toUri, conf)

    val osDoc = fs.create(pathDoc)
    val osDocIds = new ObjectOutputStream(fs.create(pathDocIds))

    documentBuffer.writeToStream(osDoc)
    osDocIds.writeObject(keys)

    osDoc.close()
    osDocIds.close()

    // Write annotation buffer
    val pathAnnot = new Path(location + ".sannots")
    val osAnnot = fs.create(pathAnnot)
    annotationBuffer.writeToStream(osAnnot)
    osAnnot.close()

    // Write annotation TOC
    val pathAnnotToc = new Path(location + ".sannots.toc")
    val osAnnotToc = fs.create(pathAnnotToc)
    annotBufferMap.foreach(kv => osAnnotToc.writeBytes(s"${kv._1}\t${kv._2._1}\t${kv._2._2}\n"))
    osAnnotToc.close()

  }

  /**
    * Get an estimate of the partition size.
    *
    * @return An estimate of the estimated partition size.
    */
  override def estimatedSize: Long = {
    val docSize = documentBuffer.getCompressedSize
    val annotSize = annotationBuffer.getCompressedSize
    val docIdsSize = SizeEstimator.estimate(keys)
    docSize + annotSize + docIdsSize
  }

  /**
    * Find the index of a particular key using binary search.
    *
    * @param key The key to find.
    * @return Position of the key in the list of keys.
    */
  def findKey(key: String): Int = {
    var (low, high) = (0, keys.length - 1)

    while (low <= high)
      (low + high) / 2 match {
        case mid if Ordering.String.gt(keys(mid), key) => high = mid - 1
        case mid if Ordering.String.lt(keys(mid), key) => low = mid + 1
        case mid => return mid
      }
    -1
  }

  /**
    * Get the document text for a given document ID.
    *
    * @param docId The document ID.
    * @return The document text.
    */
  def getDocument(docId: String): String = {
    val pos = findKey(docId)
    if (pos < 0 || pos > keys.length) null else documentBuffer.getRecord(pos)
  }

  /**
    * Extract the document text for a given document ID at a specified offset into the document.
    *
    * @param docId  The document ID.
    * @param offset The offset into the document.
    * @param length The number of characters to extract.
    * @return The extracted document text.
    */
  def extractDocument(docId: String, offset: Int, length: Int): String = {
    val pos = findKey(docId)
    if (pos < 0 || pos > keys.length) null else documentBuffer.extractRecord(pos, offset, length)
  }

  /**
    * Search for a query string in the document texts.
    *
    * @param query The query string to search for.
    * @return The location and length of the matches for each document.
    */
  def search(query: String): Iterator[Result] = {
    new Iterator[Result] {
      val searchIterator = documentBuffer.searchIterator(query.toCharArray)
      val matchLength = query.length

      override def hasNext: Boolean = searchIterator.hasNext

      override def next(): Result = {
        val offset = searchIterator.next().toInt
        val recordId = documentBuffer.offsetToRecordId(offset)
        val key = keys(recordId)
        val begin = offset - documentBuffer.getRecordOffset(recordId)
        val end = begin + matchLength
        Result(key, begin, end, null)
      }
    }
  }

  /**
    * Search for a regex pattern in the document texts.
    *
    * @param query The regex pattern to search for.
    * @return The location and length of the matches for each document.
    */
  def regexSearch(query: String): Iterator[Result] = {
    new Iterator[Result] {
      val matches = documentBuffer.regexSearch(query).iterator()

      override def hasNext: Boolean = matches.hasNext

      override def next(): Result = {
        val m = matches.next()
        val offset = m.getOffset.toInt
        val recordId = documentBuffer.offsetToRecordId(offset)
        val key = keys(recordId)
        val begin = offset - documentBuffer.getRecordOffset(recordId)
        val end = begin + m.getLength
        Result(key, begin, end, null)
      }
    }
  }

  /**
    * Filter annotations in this partition by the annotation class, annotation type and the
    * annotation metadata.
    *
    * @param annotClassFilter Regex metadataFilter on annotation class.
    * @param annotTypeFilter  Regex metadataFilter on annotation type.
    * @param metadataFilter   Arbitrary metadataFilter function on metadata.
    * @return An [[Iterator]] over the filtered annotations encapsulated as Result objects.
    */
  def filterAnnotations(annotClassFilter: String, annotTypeFilter: String,
                        metadataFilter: String => Boolean,
                        textFilter: String => Boolean): Iterator[Result] = {
    val delim = "\\" + SuccinctAnnotationBuffer.DELIM
    val keyFilter = delim + annotClassFilter + delim + annotTypeFilter + delim
    annotBufferMap.filterKeys(_ matches keyFilter).map(kv => {
      val range = kv._2
      val keyFragments = kv._1.replaceFirst("\\^", "").split("\\^")
      val annotClass = keyFragments(0)
      val annotType = keyFragments(1)
      new Iterator[Annotation] {
        var curRecordIdx = range._1 - 1
        var annotationIterator = nextAnnotationRecordIterator

        def nextAnnotationRecordIterator: Iterator[Annotation] = {
          curRecordIdx += 1
          if (curRecordIdx >= range._2) return null
          val res = annotationBuffer.getAnnotationRecord(annotClass, annotType, curRecordIdx, keys(annotationBuffer.getDocIdIndex(curRecordIdx)))
          res.iterator().asScala
        }

        override def hasNext: Boolean = annotationIterator != null

        override def next(): Annotation = {
          if (!hasNext) throw new NoSuchElementException()
          val ret = annotationIterator.next()
          if (!annotationIterator.hasNext) {
            annotationIterator = nextAnnotationRecordIterator
          }
          ret
        }
      }
    })
      .foldLeft(Iterator[Annotation]())(_ ++ _)
      .filter(a => {
        (metadataFilter == null || metadataFilter(a.getMetadata)) &&
          (textFilter == null || textFilter(extractDocument(a.getDocId, a.getStartOffset, a.getEndOffset - a.getStartOffset)))
      })
      .map(a => Result(a.getDocId, a.getStartOffset, a.getEndOffset, a))
  }

  /**
    * Generic method for handling A op B operations where A is FilterAnnotation, and B is any
    * operation
    *
    * @param annotClassFilter Regex metadataFilter on annotation class.
    * @param annotTypeFilter  Regex metadataFilter on annotation type.
    * @param metadataFilter   Arbitrary metadataFilter function on metadata.
    * @param textFilter       Arbitrary metadataFilter function on document text.
    * @param it               Result of operation B
    * @param op               The operation on A and B
    * @return An Iterator over annotations encapsulated in Result objects.
    */
  def annotationsOp(annotClassFilter: String, annotTypeFilter: String,
                    metadataFilter: String => Boolean, textFilter: String => Boolean,
                    it: Iterator[Result],
                    op: (AnnotationRecord, Int, Int) => Array[Annotation]): Iterator[Result] = {
    val delim = "\\" + SuccinctAnnotationBuffer.DELIM
    val keyFilter = delim + annotClassFilter + delim + annotTypeFilter + delim
    val kvs = annotBufferMap.filterKeys(_ matches keyFilter).toSeq

    if (kvs.isEmpty) return Iterator[Result]()

    implicit class IteratorWrapper[T](it: Iterator[T]) {
      def distinct = new Iterator[T] {
        var distinctStream = it.toStream.distinct

        override def hasNext: Boolean = distinctStream.nonEmpty

        override def next(): T = {
          val toReturn = distinctStream.head
          distinctStream = distinctStream.tail
          toReturn
        }
      }
    }

    new Iterator[Result] {
      var seenSoFar = Set[Annotation]()
      var curBufIdx = kvs.length - 1
      var curAnnotIdx = -1
      var curRes: Result = null
      var curAnnots: Array[Annotation] = nextAnnots
      var curAnnot: Annotation = nextAnnot

      def nextAnnots: Array[Annotation] = {
        var annots: Array[Annotation] = Array[Annotation]()
        while (annots.length == 0) {
          var annotRecord: AnnotationRecord = null
          while (annotRecord == null) {
            curBufIdx += 1
            if (curBufIdx == kvs.size) {
              curBufIdx = 0
              curRes = if (it.hasNext) it.next() else null
              if (!hasNext) return null
            }
            val docIdOffset = findKey(curRes.docId)
            val range = kvs(curBufIdx)._2
            val keyFragments = kvs(curBufIdx)._1.replaceFirst("\\^", "").split("\\^")
            val annotClass = keyFragments(0)
            val annotType = keyFragments(1)
            annotRecord = annotationBuffer.getAnnotationRecord(annotClass, annotType, curRes.docId, docIdOffset, range._1, range._2)
          }
          annots = op(annotRecord, curRes.startOffset, curRes.endOffset)
        }
        annots
      }

      def nextAnnot: Annotation = {
        var annot: Annotation = null
        while (annot == null) {
          curAnnotIdx += 1
          if (curAnnots != null && curAnnotIdx == curAnnots.length) {
            curAnnotIdx = 0
            curAnnots = nextAnnots
          }
          if (curAnnots == null) return null
          annot = if (seenSoFar contains curAnnots(curAnnotIdx)) {
            null
          } else {
            seenSoFar += curAnnots(curAnnotIdx)
            curAnnots(curAnnotIdx)
          }
        }
        annot
      }

      override def hasNext: Boolean = curRes != null

      override def next(): Result = {
        if (!hasNext)
          throw new NoSuchElementException()
        val annot = curAnnot
        curAnnot = nextAnnot
        Result(annot.getDocId, annot.getStartOffset, annot.getEndOffset, annot)
      }
    }.filter(r => metadataFilter(r.annotation.getMetadata))
  }

  /**
    * Get all filtered annotations that contain a given set of (docId, startOffset, endOffset)
    * triplets.
    *
    * @param annotClassFilter Regex metadataFilter on annotation class.
    * @param annotTypeFilter  Regex metadataFilter on annotation type.
    * @param metadataFilter   Arbitrary metadataFilter function on metadata.
    * @param textFilter       Arbitrary metadataFilter function on document text.
    * @param it               An iterator over the (docId, startOffset, endOffset) triplets
    *                         encapsulated in Result objects.
    * @return An iterator over the matching annotations encapsulated in Result objects.
    */
  def annotationsContainingOp(annotClassFilter: String, annotTypeFilter: String,
                              metadataFilter: String => Boolean, textFilter: String => Boolean,
                              it: Iterator[Result]): Iterator[Result] = {
    annotationsOp(annotClassFilter, annotTypeFilter, metadataFilter, textFilter, it,
      (a, start, end) => a.annotationsContaining(start, end))
  }

  /**
    * Get all filtered annotations contained in a given set of (docId, startOffset, endOffset)
    * triplets.
    *
    * @param annotClassFilter Regex metadataFilter on annotation class.
    * @param annotTypeFilter  Regex metadataFilter on annotation type.
    * @param metadataFilter   Arbitrary metadataFilter function on metadata.
    * @param textFilter       Arbitrary metadataFilter function on document text.
    * @param it               An iterator over the (docId, startOffset, endOffset) triplets
    *                         encapsulated in Result objects.
    * @return An iterator over the matching annotations encapsulated in Result objects.
    */
  def annotationsContainedInOp(annotClassFilter: String, annotTypeFilter: String,
                               metadataFilter: String => Boolean, textFilter: String => Boolean,
                               it: Iterator[Result]): Iterator[Result] = {
    annotationsOp(annotClassFilter, annotTypeFilter, metadataFilter, textFilter, it,
      (a, start, end) => a.annotationsContainedIn(start, end))
  }

  /**
    * Get all annotations that occur before a given set of (docId, startOffset, endOffset) triplets.
    *
    * @param annotClassFilter Regex metadataFilter on annotation class.
    * @param annotTypeFilter  Regex metadataFilter on annotation type.
    * @param metadataFilter   Arbitrary metadataFilter function on metadata.
    * @param textFilter       Arbitrary metadataFilter function on document text.
    * @param it               An iterator over the (docId, startOffset, endOffset) triplets
    *                         encapsulated in Result objects.
    * @param range            Max number of chars the annotation can be away from begin; -1 sets the limit
    *                         to infinity, i.e., all annotations before.
    * @return An iterator over the matching annotations encapsulated in Result objects.
    */
  def annotationsBeforeOp(annotClassFilter: String, annotTypeFilter: String,
                          metadataFilter: String => Boolean, textFilter: String => Boolean,
                          it: Iterator[Result], range: Int): Iterator[Result] = {
    annotationsOp(annotClassFilter, annotTypeFilter, metadataFilter, textFilter, it,
      (a, start, end) => a.annotationsBefore(start, end, range))
  }

  /**
    * Get all annotations that occur before a given set of (docId, startOffset, endOffset) triplets.
    *
    * @param annotClassFilter Regex metadataFilter on annotation class.
    * @param annotTypeFilter  Regex metadataFilter on annotation type.
    * @param metadataFilter   Arbitrary metadataFilter function on metadata.
    * @param textFilter       Arbitrary metadataFilter function on document text.
    * @param it               An iterator over the (docId, startOffset, endOffset) triplets
    *                         encapsulated in Result objects.
    * @param range            Max number of chars the annotation can be away from begin; -1 sets the
    *                         limit to infinity, i.e., all annotations before.
    * @return An iterator over the matching annotations encapsulated in Result objects.
    */
  def annotationsAfterOp(annotClassFilter: String, annotTypeFilter: String,
                         metadataFilter: String => Boolean, textFilter: String => Boolean,
                         it: Iterator[Result], range: Int): Iterator[Result] = {
    annotationsOp(annotClassFilter, annotTypeFilter, metadataFilter, textFilter, it,
      (a, start, end) => a.annotationsAfter(start, end, range))
  }

  /**
    * Generic method for handling A op B operations where B is FilterAnnotation, and A is any
    * operation.
    *
    * @param it               Result of operation A.
    * @param annotClassFilter Regex metadataFilter on annotation class.
    * @param annotTypeFilter  Regex metadataFilter on annotation type.
    * @param op               The operation on A and B
    * @return An Iterator over annotations encapsulated in Result objects.
    */
  def opAnnotations(it: Iterator[Result], annotClassFilter: String, annotTypeFilter: String,
                    op: (AnnotationRecord, Int, Int) => Boolean): Iterator[Result] = {
    val delim = "\\" + SuccinctAnnotationBuffer.DELIM
    val keyFilter = delim + annotClassFilter + delim + annotTypeFilter + delim
    val kvs = annotBufferMap.filterKeys(_ matches keyFilter).toSeq

    if (kvs.isEmpty) return Iterator[Result]()

    new Iterator[Result] {
      var curBufIdx = kvs.length - 1
      var curRes: Result = nextRes

      def nextRes: Result = {
        var valid: Boolean = false
        while (!valid) {
          var annotRecord: AnnotationRecord = null
          while (annotRecord == null) {
            curBufIdx += 1
            if (curBufIdx == kvs.size) {
              curBufIdx = 0
              curRes = if (it.hasNext) it.next() else null
              if (!hasNext) return null
            }
            val docIdOffset = findKey(curRes.docId)
            val range = kvs(curBufIdx)._2
            val keyFragments = kvs(curBufIdx)._1.replaceFirst("\\^", "").split("\\^")
            val annotClass = keyFragments(0)
            val annotType = keyFragments(1)
            annotRecord = annotationBuffer.getAnnotationRecord(annotClass, annotType, curRes.docId, docIdOffset, range._1, range._2)
          }
          valid = op(annotRecord, curRes.startOffset, curRes.endOffset)
        }
        curRes
      }

      override def hasNext: Boolean = curRes != null

      override def next(): Result = {
        if (!hasNext)
          throw new NoSuchElementException()

        val toReturn = curRes
        curRes = nextRes
        toReturn
      }
    }
  }

  /**
    * Get a new instance of AnnotationFilter object which wraps an input lambda metadataFilter function.
    *
    * @param mFilter The input lambda metadataFilter function.
    * @return A AnnotationFilter object.
    */
  def newAnnotationFilter(mFilter: String => Boolean,
                          tFilter: String => Boolean): AnnotationFilter = {
    class AnnotationFilterWrapper extends AnnotationFilter {

      override def metadataFilter(metadata: String): Boolean = {
        if (mFilter == null) true
        else mFilter(metadata)
      }

      override def textFilter(docId: String, startOffset: Int, endOffset: Int): Boolean = {
        if (tFilter == null) true
        else tFilter(extractDocument(docId, startOffset, endOffset - startOffset))
      }
    }
    new AnnotationFilterWrapper
  }

  /**
    * Get all (docId, startOffset, endOffset) triplets that contain certain filtered annotations.
    *
    * @param it               An iterator over the (docId, startOffset, endOffset) triplets
    *                         encapsulated in Result objects.
    * @param annotClassFilter Regex metadataFilter on annotation class.
    * @param annotTypeFilter  Regex metadataFilter on annotation type.
    * @param metadataFilter   Arbitrary metadataFilter function on metadata.
    * @param textFilter       Arbitrary metadataFilter function on document text.
    * @return An iterator over the matching (docId, startOffset, endOffset) triplets encapsulated
    *         in Result objects.
    */
  def opContainingAnnotations(it: Iterator[Result], annotClassFilter: String,
                              annotTypeFilter: String, metadataFilter: String => Boolean,
                              textFilter: String => Boolean): Iterator[Result] = {
    // Use the inverse containedIn function to check if annotation is contained in the results.
    opAnnotations(it, annotClassFilter, annotTypeFilter,
      (a, start, end) => a.containedIn(start, end, newAnnotationFilter(metadataFilter, textFilter)))
  }

  /**
    * Get all (docId, startOffset, endOffset) triplets that are contained in certain filtered
    * annotations.
    *
    * @param it               An iterator over the (docId, startOffset, endOffset) triplets
    *                         encapsulated in Result objects.
    * @param annotClassFilter Regex metadataFilter on annotation class.
    * @param annotTypeFilter  Regex metadataFilter on annotation type.
    * @param metadataFilter   Arbitrary metadataFilter function on metadata.
    * @param textFilter       Arbitrary metadataFilter function on document text.
    * @return An iterator over the matching (docId, startOffset, endOffset) triplets encapsulated
    *         in Result objects.
    */
  def opContainedInAnnotations(it: Iterator[Result], annotClassFilter: String,
                               annotTypeFilter: String, metadataFilter: String => Boolean,
                               textFilter: String => Boolean): Iterator[Result] = {
    // Use the inverse containing function to check if annotations contain the results.
    opAnnotations(it, annotClassFilter, annotTypeFilter,
      (a, start, end) => a.contains(start, end, newAnnotationFilter(metadataFilter, textFilter)))
  }

  /**
    * Get all (docId, startOffset, endOffset) triplets that are before certain filtered annotations.
    *
    * @param it               An iterator over the (docId, startOffset, endOffset) triplets
    *                         encapsulated in Result objects.
    * @param annotClassFilter Regex metadataFilter on annotation class.
    * @param annotTypeFilter  Regex metadataFilter on annotation type.
    * @param metadataFilter   Arbitrary metadataFilter function on metadata.
    * @param textFilter       Arbitrary metadataFilter function on document text.
    * @param range            Max number of chars the annotation can be away from begin; -1 sets the
    *                         limit to infinity, i.e., all annotations before.
    * @return An iterator over the matching (docId, startOffset, endOffset) triplets encapsulated
    *         in Result objects.
    */
  def opBeforeAnnotations(it: Iterator[Result], annotClassFilter: String,
                          annotTypeFilter: String, metadataFilter: String => Boolean,
                          textFilter: String => Boolean, range: Int): Iterator[Result] = {
    // Use the inverse containing function to check if annotations occur after the results.
    opAnnotations(it, annotClassFilter, annotTypeFilter,
      (a, start, end) => a.after(start, end, range, newAnnotationFilter(metadataFilter, textFilter)))
  }

  /**
    * Get all (docId, startOffset, endOffset) triplets that are after certain filtered annotations.
    *
    * @param it               An iterator over the (docId, startOffset, endOffset) triplets
    *                         encapsulated in Result objects.
    * @param annotClassFilter Regex metadataFilter on annotation class.
    * @param annotTypeFilter  Regex metadataFilter on annotation type.
    * @param metadataFilter   Arbitrary metadataFilter function on metadata.
    * @param textFilter       Arbitrary metadataFilter function on document text.
    * @param range            Max number of chars the annotation can be away from end; -1 sets the
    *                         limit to infinity, i.e., all annotations after.
    * @return An iterator over the matching (docId, startOffset, endOffset) triplets encapsulated
    *         in Result objects.
    */
  def opAfterAnnotations(it: Iterator[Result], annotClassFilter: String,
                         annotTypeFilter: String, metadataFilter: String => Boolean,
                         textFilter: String => Boolean, range: Int): Iterator[Result] = {
    // Use the inverse containing function to check if annotations occur before the results.
    opAnnotations(it, annotClassFilter, annotTypeFilter,
      (a, start, end) => a.before(start, end, range, newAnnotationFilter(metadataFilter, textFilter)))
  }

  /**
    * Generic binary operation A op B, where A and B are results of arbitrary operations.
    *
    * @param it1       Result of operation A.
    * @param it2       Result of operation B.
    * @param condition The condition which must be satisfied for operation A op B.
    * @return An iterator over matching results.
    */
  def binaryOp(it1: Iterator[Result], it2: Iterator[Result],
               condition: (Result, Result) => Boolean): Iterator[Result] = {
    // TODO: We can optimize if both iterators are sorted
    new Iterator[Result]() {
      val it2Stream = it2.toStream
      var curRes: Result = nextRes

      def nextRes: Result = {
        var valid: Boolean = false
        while (!valid) {
          curRes = if (it1.hasNext) it1.next() else return null
          valid = checkCondition
        }
        curRes
      }

      def checkCondition: Boolean = {
        for (r <- it2Stream) {
          if (curRes.docId == r.docId && condition(curRes, r)) return true
        }
        false
      }

      override def hasNext: Boolean = curRes != null

      override def next(): Result = {
        if (!hasNext)
          throw new NoSuchElementException()

        val toReturn = curRes
        curRes = nextRes
        toReturn
      }
    }
  }

  /**
    * Get all results that contain certain other results.
    *
    * @param it1 First operand.
    * @param it2 Second operand.
    * @return Iterator over matching results.
    */
  def opContainingOp(it1: Iterator[Result], it2: Iterator[Result]): Iterator[Result] = {
    binaryOp(it1, it2, (r1, r2) => r1.startOffset <= r2.startOffset && r1.endOffset >= r2.endOffset)
  }

  /**
    * Get all results that are contained in certain other results.
    *
    * @param it1 First operand.
    * @param it2 Second operand.
    * @return Iterator over matching results.
    */
  def opContainedInOp(it1: Iterator[Result], it2: Iterator[Result]): Iterator[Result] = {
    binaryOp(it1, it2, (r1, r2) => r1.startOffset >= r2.startOffset && r1.endOffset <= r2.endOffset)
  }

  /**
    * Get all results that are before certain other results.
    *
    * @param it1 First operand.
    * @param it2 Second operand.
    * @return Iterator over matching results.
    */
  def opBeforeOp(it1: Iterator[Result], it2: Iterator[Result], range: Int): Iterator[Result] = {
    binaryOp(it1, it2, (r1, r2) => r1.endOffset <= r2.startOffset && !(range != -1 && r2.startOffset - r1.endOffset > range))
  }

  /**
    * Get all results that are after certain other results.
    *
    * @param it1 First operand.
    * @param it2 Second operand.
    * @return Iterator over matching results.
    */
  def opAfterOp(it1: Iterator[Result], it2: Iterator[Result], range: Int): Iterator[Result] = {
    binaryOp(it1, it2, (r1, r2) => r1.startOffset >= r2.endOffset && !(range != -1 && r1.startOffset - r2.endOffset > range))
  }

  /**
    * Find all (documentID, startOffset, endOffset) triplets corresponding to an arbitrary query
    * composed of Contains, ContainedIn, Before, After, FilterAnnotations, Search and RegexSearch
    * queries.
    *
    * @param operator An arbitrary expression tree composed of Contains, ContainedIn, Before, After,
    *                 FilterAnnotations, Search and RegexSearch.
    * @return An [[Iterator]] over matching (documentID, startOffset, endOffset) triplets
    *         encapsulated as Result objects.
    */
  def query(operator: Operator): Iterator[Result] = {
    operator match {
      case Search(query) => search(query)
      case Regex(query) => regexSearch(query)
      case FilterAnnotations(acFilter, atFilter, mFilter, tFilter) =>
        filterAnnotations(acFilter, atFilter, mFilter, tFilter)
      case Contains(a, b) =>
        (a, b) match {
          case (_, FilterAnnotations(acFilter, atFilter, mFilter, tFilter)) =>
            opContainingAnnotations(query(a), acFilter, atFilter, mFilter, tFilter)
          //          case (FilterAnnotations(acFilter, atFilter, mFilter, tFilter), _) =>
          //            annotationsContainingOp(acFilter, atFilter, mFilter, tFilter, query(b))
          case _ => opContainingOp(query(a), query(b))
        }
      case ContainedIn(a, b) =>
        (a, b) match {
          case (_, FilterAnnotations(acFilter, atFilter, mFilter, tFilter)) =>
            opContainedInAnnotations(query(a), acFilter, atFilter, mFilter, tFilter)
          //          case (FilterAnnotations(acFilter, atFilter, mFilter, tFilter), _) =>
          //            annotationsContainedInOp(acFilter, atFilter, mFilter, tFilter, query(b))
          case _ => opContainedInOp(query(a), query(b))
        }
      case Before(a, b, range) =>
        (a, b) match {
          case (_, FilterAnnotations(acFilter, atFilter, mFilter, tFilter)) =>
            opBeforeAnnotations(query(a), acFilter, atFilter, mFilter, tFilter, range)
          //          case (FilterAnnotations(acFilter, atFilter, mFilter, tFilter), _) =>
          //            annotationsBeforeOp(acFilter, atFilter, mFilter, tFilter, query(b), range)
          case _ => opBeforeOp(query(a), query(b), range)
        }
      case After(a, b, range) =>
        (a, b) match {
          case (_, FilterAnnotations(acFilter, atFilter, mFilter, tFilter)) =>
            opAfterAnnotations(query(a), acFilter, atFilter, mFilter, tFilter, range)
          //          case (FilterAnnotations(acFilter, atFilter, mFilter, tFilter), _) =>
          //            annotationsAfterOp(acFilter, atFilter, mFilter, tFilter, query(b), range)
          case _ => opAfterOp(query(a), query(b), range)
        }
      case unknown => throw new UnsupportedOperationException(s"Operation $unknown not supported.")
    }
  }

  /**
    * Get the first and the last document ID in this partition.
    *
    * @return The first and last document IDs in the partition.
    */
  def getDocIdRange: (String, String) = (keys(0), keys(keys.length - 1))

  /**
    * Get the number of documents in the partition.
    *
    * @return The number of documents in the partition.
    */
  def count: Int = keys.length

  def storageBreakdown(): String = {
    val mb = 1024*1024
    val keysSize = keys.map(_.length).sum
    val docsSize = documentBuffer.getSize / mb
    val docsSizeCompresseed = documentBuffer.getCompressedSize / mb
    val annotSize = annotationBuffer.getOriginalSize / mb
    val annotSizeCompressed = annotationBuffer.getCompressedSize / mb
    "keys: " + keysSize +
      "\tdocs: (compressed mb) " + docsSizeCompresseed + " (uncompressed mb) " + docsSize +
      "\tannots: (compressed mb) " + annotSizeCompressed + " (uncompressed mb) " + annotSize
  }
}

object AnnotatedSuccinctPartition {
  def apply(partitionLocation: String, annotClassFilter: String, annotTypeFilter: String)
  : AnnotatedSuccinctPartition = {

    val pathDoc = new Path(partitionLocation + ".sdocs")
    val pathDocIds = new Path(partitionLocation + ".sdocids")

    val fs = FileSystem.get(pathDoc.toUri, new Configuration())

    val isDoc = fs.open(pathDoc)
    val isDocIds = new ObjectInputStream(fs.open(pathDocIds))

    val documentBuffer = new SuccinctIndexedFileBuffer(isDoc)
    val keys = isDocIds.readObject().asInstanceOf[Array[String]]

    isDoc.close()
    isDocIds.close()

    val delim = "\\" + SuccinctAnnotationBuffer.DELIM
    val keyFilter = delim + annotClassFilter + delim + annotTypeFilter + delim
    val pathAnnotToc = new Path(partitionLocation + ".sannots.toc")
    val pathAnnot = new Path(partitionLocation + ".sannots")
    val isAnnotToc = fs.open(pathAnnotToc)
    val annotMap = Source.fromInputStream(isAnnotToc).getLines().map(_.split('\t'))
      .map(e => (e(0), (e(1).toInt, e(2).toInt)))
      .filter(e => e._1 matches keyFilter)
      .toMap
    val isAnnot = fs.open(pathAnnot)
    val fileSize = fs.getFileStatus(pathAnnot).getLen.toInt
    val annotationBuffer = new SuccinctAnnotationBuffer(isAnnot, fileSize)

    new AnnotatedSuccinctPartition(keys, documentBuffer, annotationBuffer, annotMap)
  }
}
