package edu.berkeley.cs.succinct.annot

import com.google.common.io.Files
import edu.berkeley.cs.succinct.LocalSparkContext
import org.apache.spark.{SparkConf, SparkContext}
import org.scalatest.FunSuite

class AnnotatedSuccinctRDDSuite extends FunSuite with LocalSparkContext {

  val conf = new SparkConf().setAppName("test").setMaster("local")
    .set("spark.driver.allowMultipleContexts", "true")

  val doc1 = ("doc1", "Document number one",
    "1^ge^word^0^8^foo\n2^ge^space^8^9\n3^ge^word^9^15^bar\n4^ge^space^15^16\n5^ge^word^16^19^baz")
  val doc2 = ("doc2", "Document number two",
    "1^ge^word^0^8\n2^ge^space^8^9\n3^ge^word^9^15\n4^ge^space^15^16\n5^ge^word^16^19")
  val doc3 = ("doc3", "Document number three",
    "1^ge^word^0^8^a\n2^ge^space^8^9\n3^ge^word^9^15^b&c\n4^ge^space^15^16\n5^ge^word^16^21^d^e")
  val data: Seq[(String, String, String)] = Seq(doc1, doc2, doc3)
  val docMap: Map[String, String] = data.map(d => (d._1, d._2)).toMap

  test("Test getDocument") {
    sc = new SparkContext(conf)

    val annotatedRDD = sc.parallelize(data)
    val annotatedSuccinctRDD = AnnotatedSuccinctRDD(annotatedRDD)

    // Check
    data.map(_._1).foreach(docId => {
      val docText = annotatedSuccinctRDD.getDocument(docId)
      assert(docText == docMap(docId))
    })
  }

  test("Test extractDocument") {
    sc = new SparkContext(conf)

    val annotatedRDD = sc.parallelize(data)
    val annotatedSuccinctRDD = AnnotatedSuccinctRDD(annotatedRDD)

    // Check
    data.map(_._1).foreach(docId => {
      val docText = annotatedSuccinctRDD.extractDocument(docId, 9, 6)
      assert(docText == "number")
    })
  }

  test("Test Search") {
    sc = new SparkContext(conf)

    val annotatedRDD = sc.parallelize(data)
    val annotatedSuccinctRDD = AnnotatedSuccinctRDD(annotatedRDD)

    // Check
    val res1 = annotatedSuccinctRDD.query(Search("Document")).collect()
    assert(res1 contains Result("doc1", 0, 8, null))
    assert(res1 contains Result("doc2", 0, 8, null))
    assert(res1 contains Result("doc3", 0, 8, null))
    assert(res1.length == 3)
    assert(annotatedSuccinctRDD.count(Search("Document")) == 3)

    val res2 = annotatedSuccinctRDD.query(Search("number")).collect()
    assert(res2 contains Result("doc1", 9, 15, null))
    assert(res2 contains Result("doc2", 9, 15, null))
    assert(res2 contains Result("doc3", 9, 15, null))
    assert(res2.length == 3)
    assert(annotatedSuccinctRDD.count(Search("number")) == 3)

    val res3 = annotatedSuccinctRDD.query(Search("three")).collect()
    assert(res3 contains Result("doc3", 16, 21, null))
    assert(res3.length == 1)
    assert(annotatedSuccinctRDD.count(Search("three")) == 1)

    val res4 = annotatedSuccinctRDD.query(Search("four")).collect()
    assert(res4.length == 0)
    assert(annotatedSuccinctRDD.count(Search("four")) == 0)
  }

  test("Test Regex") {
    sc = new SparkContext(conf)

    val annotatedRDD = sc.parallelize(data)
    val annotatedSuccinctRDD = AnnotatedSuccinctRDD(annotatedRDD)

    // Check
    val res1 = annotatedSuccinctRDD.query(Regex("one|two")).collect()
    assert(res1 contains Result("doc1", 16, 19, null))
    assert(res1 contains Result("doc2", 16, 19, null))
    assert(res1.length == 2)
    assert(annotatedSuccinctRDD.count(Regex("one|two")) == 2)

    val res2 = annotatedSuccinctRDD.query(Regex("two|three")).collect()
    assert(res2 contains Result("doc2", 16, 19, null))
    assert(res2 contains Result("doc3", 16, 21, null))
    assert(res2.length == 2)
    assert(annotatedSuccinctRDD.count(Regex("two|three")) == 2)

    val res3 = annotatedSuccinctRDD.query(Regex("four|five|six")).collect()
    assert(res3.length == 0)
    assert(annotatedSuccinctRDD.count(Regex("four|five|six")) == 0)
  }

  test("Test FilterAnnotations") {
    sc = new SparkContext(conf)

    val annotatedRDD = sc.parallelize(data)
    val annotatedSuccinctRDD = AnnotatedSuccinctRDD(annotatedRDD)

    val geWords = annotatedSuccinctRDD.query(FilterAnnotations("ge", "word")).collect()
    assert(geWords.length == 9)
    geWords.foreach(r => {
      assert(r.annotation.getAnnotClass == "ge")
      assert(r.annotation.getAnnotType == "word")
    })
    assert(annotatedSuccinctRDD.count(FilterAnnotations("ge", "word")) == 9)

    val geSpaces = annotatedSuccinctRDD.query(FilterAnnotations("ge", "space")).collect()
    assert(geSpaces.length == 6)
    geSpaces.foreach(r => {
      assert(r.annotation.getAnnotClass == "ge")
      assert(r.annotation.getAnnotType == "space")
    })
    assert(annotatedSuccinctRDD.count(FilterAnnotations("ge", "space")) == 6)

    val geAll = annotatedSuccinctRDD.query(FilterAnnotations("ge", ".*")).collect()
    assert(geAll.length == 15)
    geAll.foreach(a => {
      assert(a.annotation.getAnnotClass == "ge")
      assert(a.annotation.getAnnotType == "word" || a.annotation.getAnnotType == "space")
    })
    assert(annotatedSuccinctRDD.count(FilterAnnotations("ge", ".*")) == 15)

    val geOr = annotatedSuccinctRDD.query(FilterAnnotations("ge", "word|space")).collect()
    assert(geOr.length == 15)
    geOr.foreach(a => {
      assert(a.annotation.getAnnotClass == "ge")
      assert(a.annotation.getAnnotType == "word" || a.annotation.getAnnotType == "space")
    })
    assert(annotatedSuccinctRDD.count(FilterAnnotations("ge", "word|space")) == 15)

    val geWords2 = annotatedSuccinctRDD.query(FilterAnnotations("ge", "word", _.contains("ba"))).collect()
    assert(geWords2.length == 2)
    geWords2.foreach(r => {
      assert(r.annotation.getAnnotClass == "ge")
      assert(r.annotation.getAnnotType == "word")
      assert(r.annotation.getMetadata.startsWith("ba"))
    })

    val geWords3 = annotatedSuccinctRDD.query(FilterAnnotations("ge", "word", _.contains("ba"), _.contains("number"))).collect()
    assert(geWords3.length == 1)
    geWords3.foreach(r => {
      assert(r.annotation.getAnnotClass == "ge")
      assert(r.annotation.getAnnotType == "word")
      assert(r.annotation.getMetadata.startsWith("ba"))
      assert(annotatedSuccinctRDD.extractDocument(r.docId, r.startOffset, r.endOffset - r.startOffset) == "number")
    })
  }

  test("Test Contains(FilterAnnotations, Search)") {
    sc = new SparkContext(conf)

    val annotatedRDD = sc.parallelize(data)
    val annotatedSuccinctRDD = AnnotatedSuccinctRDD(annotatedRDD)

    // Check
    val query1 = Contains(FilterAnnotations("ge", "word"), Search("Document"))
    val res1 = annotatedSuccinctRDD.query(query1).collect()
    assert(res1.length == 3)
    res1.foreach(a => {
      assert(a.annotation.getStartOffset == 0)
      assert(a.annotation.getEndOffset == 8)

    })

    val query2 = Contains(FilterAnnotations("ge", "word"), Search("number"))
    val res2 = annotatedSuccinctRDD.query(query2).collect()
    assert(res2.length == 3)
    res2.foreach(a => {
      assert(a.annotation.getStartOffset == 9)
      assert(a.annotation.getEndOffset == 15)

    })

    val query3 = Contains(FilterAnnotations("ge", "word"), Search("three"))
    val res3 = annotatedSuccinctRDD.query(query3).collect()
    assert(res3.length == 1)
    assert(res3(0).annotation.getStartOffset == 16)
    assert(res3(0).annotation.getEndOffset == 21)
    assert(res3(0).annotation.getMetadata == "d^e")

    val query4 = Contains(FilterAnnotations("ge", "space"), Search(" "))
    val res4 = annotatedSuccinctRDD.query(query4).collect()
    assert(res4.length == 6)
    res4.foreach(a => {

      assert(a.annotation.getStartOffset == 8 || a.annotation.getStartOffset == 15)
      assert(a.annotation.getEndOffset == 9 || a.annotation.getEndOffset == 16)
      assert(a.annotation.getMetadata == "")
    })

    val query5 = Contains(FilterAnnotations("ge", "word"), Search("four"))
    val res5 = annotatedSuccinctRDD.query(query5).collect()
    assert(res5.length == 0)

    val query6 = Contains(FilterAnnotations("ge", "word"), Search("e"))
    val res6 = annotatedSuccinctRDD.query(query6).collect()
    assert(res6.length == 8)

    val query7 = Contains(FilterAnnotations("ge", "word", _.nonEmpty, _.contains("m")), Search("Document"))
    val res7 = annotatedSuccinctRDD.query(query7).collect()
    assert(res7.length == 2)
  }

  test("Test Contains(FilterAnnotations, Regex)") {
    sc = new SparkContext(conf)

    val annotatedRDD = sc.parallelize(data)
    val annotatedSuccinctRDD = AnnotatedSuccinctRDD(annotatedRDD)

    // Check
    val query1 = Contains(FilterAnnotations("ge", "word"), Regex("one|two|three"))
    val res1 = annotatedSuccinctRDD.query(query1).collect()
    assert(res1.length == 3)
    res1.foreach(a => {
      assert(a.annotation.getStartOffset == 16)
      assert(a.annotation.getEndOffset == 19 | a.annotation.getEndOffset == 21)

    })

    val query2 = Contains(FilterAnnotations("ge", "word"), Regex("four|five|six"))
    val res2 = annotatedSuccinctRDD.query(query2).collect()
    assert(res2.length == 0)
  }

  test("Test ContainedIn(FilterAnnotations, Search)") {
    sc = new SparkContext(conf)

    val annotatedRDD = sc.parallelize(data)
    val annotatedSuccinctRDD = AnnotatedSuccinctRDD(annotatedRDD)

    // Check
    val query1 = ContainedIn(FilterAnnotations("ge", "word"), Search("Document"))
    val res1 = annotatedSuccinctRDD.query(query1).collect()
    assert(res1.length == 3)
    res1.foreach(a => {
      assert(a.annotation.getStartOffset == 0)
      assert(a.annotation.getEndOffset == 8)
    })

    val query2 = ContainedIn(FilterAnnotations("ge", "word"), Search("number"))
    val res2 = annotatedSuccinctRDD.query(query2).collect()
    assert(res2.length == 3)
    res2.foreach(a => {
      assert(a.annotation.getStartOffset == 9)
      assert(a.annotation.getEndOffset == 15)
    })

    val query3 = ContainedIn(FilterAnnotations("ge", "word"), Search("number three"))
    val res3 = annotatedSuccinctRDD.query(query3).collect()
    assert(res3.length == 2)
    res3.foreach(a => {
      assert(a.annotation.getStartOffset == 9 || a.annotation.getStartOffset == 16)
      assert(a.annotation.getEndOffset == 15 || a.annotation.getEndOffset == 21)

    })

    val query4 = ContainedIn(FilterAnnotations("ge", "space"), Search("Document number"))
    val res4 = annotatedSuccinctRDD.query(query4).collect()
    assert(res4.length == 3)
    res4.foreach(a => {
      assert(a.annotation.getStartOffset == 8)
      assert(a.annotation.getEndOffset == 9)
      assert(a.annotation.getMetadata == "")
    })

    val query5 = ContainedIn(FilterAnnotations("ge", "word"), Search("ocument"))
    val res5 = annotatedSuccinctRDD.query(query5).collect()
    assert(res5.length == 0)

    val query6 = ContainedIn(FilterAnnotations("ge", "word", _.nonEmpty, _.contains("m")), Search("number three"))
    val res6 = annotatedSuccinctRDD.query(query6).collect()
    assert(res6.length == 1)
    res6.foreach(a => {
      assert(a.annotation.getStartOffset == 9)
      assert(a.annotation.getEndOffset == 15)
    })
  }

  test("Test Before(FilterAnnotations, Search)") {
    sc = new SparkContext(conf)

    val annotatedRDD = sc.parallelize(data)
    val annotatedSuccinctRDD = AnnotatedSuccinctRDD(annotatedRDD)

    // Check
    val query1 = Before(FilterAnnotations("ge", "word"), Search("Document"))
    val res1 = annotatedSuccinctRDD.query(query1).collect()
    assert(res1.length == 0)

    val query2 = Before(FilterAnnotations("ge", "word"), Search("number"))
    val res2 = annotatedSuccinctRDD.query(query2).collect()
    assert(res2.length == 3)
    res2.foreach(a => {
      assert(a.annotation.getStartOffset == 0)
      assert(a.annotation.getEndOffset == 8)

    })

    val query3 = Before(FilterAnnotations("ge", "word"), Search("number three"))
    val res3 = annotatedSuccinctRDD.query(query3).collect()
    assert(res3.length == 1)
    res3.foreach(a => {
      assert(a.annotation.getStartOffset == 0)
      assert(a.annotation.getEndOffset == 8)

    })

    val query4 = Before(FilterAnnotations("ge", "space"), Search("three"), 1)
    val res4 = annotatedSuccinctRDD.query(query4).collect()
    assert(res4.length == 1)
    res4.foreach(a => {

      assert(a.annotation.getStartOffset == 15)
      assert(a.annotation.getEndOffset == 16)
      assert(a.annotation.getMetadata == "")
    })

    val query5 = Before(FilterAnnotations("ge", "word", _.nonEmpty, _.contains("cumen")), Search("three"))
    val res5 = annotatedSuccinctRDD.query(query5).collect()
    assert(res5.length == 1)
    res5.foreach(a => {

      assert(a.annotation.getStartOffset == 0)
      assert(a.annotation.getEndOffset == 8)
      assert(a.annotation.getMetadata == "a")
    })
  }

  test("Test After(FilterAnnotations, Search)") {
    sc = new SparkContext(conf)

    val annotatedRDD = sc.parallelize(data)
    val annotatedSuccinctRDD = AnnotatedSuccinctRDD(annotatedRDD)

    // Check
    val query1 = After(FilterAnnotations("ge", "space"), Search("Document"))
    val res1 = annotatedSuccinctRDD.query(query1).collect()
    assert(res1.length == 6)
    res1.foreach(a => {

      assert(a.annotation.getStartOffset == 8 || a.annotation.getStartOffset == 15)
      assert(a.annotation.getEndOffset == 9 || a.annotation.getEndOffset == 16)
      assert(a.annotation.getMetadata == "")
    })

    val query2 = After(FilterAnnotations("ge", "word"), Search("number"))
    val res2 = annotatedSuccinctRDD.query(query2).collect()
    assert(res2.length == 3)
    res2.foreach(a => {
      assert(a.annotation.getStartOffset == 16)
      assert(a.annotation.getEndOffset == 19 || a.annotation.getEndOffset == 21)

    })

    val query3 = After(FilterAnnotations("ge", "word"), Search("number three"))
    val res3 = annotatedSuccinctRDD.query(query3).collect()
    assert(res3.length == 0)

    val query4 = After(FilterAnnotations("ge", "space"), Search("Document"), 1)
    val res4 = annotatedSuccinctRDD.query(query4).collect()
    assert(res4.length == 3)
    res4.foreach(a => {

      assert(a.annotation.getStartOffset == 8)
      assert(a.annotation.getEndOffset == 9)
      assert(a.annotation.getMetadata == "")
    })

    val query5 = After(FilterAnnotations("ge", "word", _.nonEmpty, _.contains("umber")), Search("Document"))
    val res5 = annotatedSuccinctRDD.query(query5).collect()
    assert(res5.length == 2)
    res5.foreach(a => {

      assert(a.annotation.getStartOffset == 9)
      assert(a.annotation.getEndOffset == 15)
      assert(a.annotation.getMetadata.nonEmpty)
    })
  }

  test("Test Contains(Search, FilterAnnotations)") {
    sc = new SparkContext(conf)

    val annotatedRDD = sc.parallelize(data)
    val annotatedSuccinctRDD = AnnotatedSuccinctRDD(annotatedRDD)

    // Check
    val query1 = Contains(Search("Document"), FilterAnnotations("ge", "word"))
    val res1 = annotatedSuccinctRDD.query(query1).collect()
    assert(res1.length == 3)
    res1.foreach(a => {
      assert(a.startOffset == 0)
      assert(a.endOffset == 8)
      assert(a.annotation == null)
    })

    val query2 = Contains(Search("number"), FilterAnnotations("ge", "word"))
    val res2 = annotatedSuccinctRDD.query(query2).collect()
    assert(res2.length == 3)
    res2.foreach(a => {
      assert(a.startOffset == 9)
      assert(a.endOffset == 15)
      assert(a.annotation == null)
    })

    val query3 = Contains(Search("number three"), FilterAnnotations("ge", "word"))
    val res3 = annotatedSuccinctRDD.query(query3).collect()
    assert(res3.length == 1)
    res3.foreach(a => {
      assert(a.startOffset == 9)
      assert(a.endOffset == 21)
      assert(a.annotation == null)
    })

    val query4 = Contains(Search("Document number"), FilterAnnotations("ge", "space"))
    val res4 = annotatedSuccinctRDD.query(query4).collect()
    assert(res4.length == 3)
    res4.foreach(a => {
      assert(a.startOffset == 0)
      assert(a.endOffset == 15)
      assert(a.annotation == null)
    })

    val query5 = Contains(Search("ocument"), FilterAnnotations("ge", "word"))
    val res5 = annotatedSuccinctRDD.query(query5).collect()
    assert(res5.length == 0)

    val query6 = Contains(Search("number three"), FilterAnnotations("ge", "word", _.nonEmpty, _.contains("two")))
    val res6 = annotatedSuccinctRDD.query(query6).collect()
    assert(res6.length == 0)
  }

  test("Test ContainedIn(Search, FilterAnnotations)") {
    sc = new SparkContext(conf)

    val annotatedRDD = sc.parallelize(data)
    val annotatedSuccinctRDD = AnnotatedSuccinctRDD(annotatedRDD)

    // Check
    val query1 = ContainedIn(Search("Document"), FilterAnnotations("ge", "word"))
    val res1 = annotatedSuccinctRDD.query(query1).collect()
    assert(res1.length == 3)
    res1.foreach(a => {
      assert(a.startOffset == 0)
      assert(a.endOffset == 8)
      assert(a.annotation == null)
    })

    val query2 = ContainedIn(Search("number"), FilterAnnotations("ge", "word"))
    val res2 = annotatedSuccinctRDD.query(query2).collect()
    assert(res2.length == 3)
    res2.foreach(a => {
      assert(a.startOffset == 9)
      assert(a.endOffset == 15)
      assert(a.annotation == null)
    })

    val query3 = ContainedIn(Search("three"), FilterAnnotations("ge", "word"))
    val res3 = annotatedSuccinctRDD.query(query3).collect()
    assert(res3.length == 1)
    assert(res3(0).startOffset == 16)
    assert(res3(0).endOffset == 21)
    assert(res3(0).annotation == null)

    val query4 = ContainedIn(Search(" "), FilterAnnotations("ge", "space"))
    val res4 = annotatedSuccinctRDD.query(query4).collect()
    assert(res4.length == 6)
    res4.foreach(a => {
      assert(a.startOffset == 8 || a.startOffset == 15)
      assert(a.endOffset == 9 || a.endOffset == 16)
      assert(a.annotation == null)
    })

    val query5 = ContainedIn(Search("four"), FilterAnnotations("ge", "word"))
    val res5 = annotatedSuccinctRDD.query(query5).collect()
    assert(res5.length == 0)

    val query6 = ContainedIn(Search("Document"), FilterAnnotations("ge", "word", _.nonEmpty, _.contains("m")))
    val res6 = annotatedSuccinctRDD.query(query6).collect()
    assert(res6.length == 2)
  }

  test("Test Before(Search, FilterAnnotations)") {
    sc = new SparkContext(conf)

    val annotatedRDD = sc.parallelize(data)
    val annotatedSuccinctRDD = AnnotatedSuccinctRDD(annotatedRDD)

    // Check
    val query1 = Before(Search("Document"), FilterAnnotations("ge", "space"))
    val res1 = annotatedSuccinctRDD.query(query1).collect()
    assert(res1.length == 3)
    res1.foreach(a => {
      assert(a.startOffset == 0)
      assert(a.endOffset == 8)
      assert(a.annotation == null)
    })

    val query2 = Before(Search("number"), FilterAnnotations("ge", "word"))
    val res2 = annotatedSuccinctRDD.query(query2).collect()
    assert(res2.length == 3)
    res2.foreach(a => {
      assert(a.startOffset == 9)
      assert(a.endOffset == 15)
      assert(a.annotation == null)
    })

    val query3 = Before(Search("number three"), FilterAnnotations("ge", "word"))
    val res3 = annotatedSuccinctRDD.query(query3).collect()
    assert(res3.length == 0)

    val query4 = Before(Search("Document"), FilterAnnotations("ge", "space"), 1)
    val res4 = annotatedSuccinctRDD.query(query4).collect()
    assert(res4.length == 3)
    res4.foreach(a => {
      assert(a.startOffset == 0)
      assert(a.endOffset == 8)
      assert(a.annotation == null)
    })

    val query5 = Before(Search("Document"), FilterAnnotations("ge", "word", _.nonEmpty, _.contains("umber")))
    val res5 = annotatedSuccinctRDD.query(query5).collect()
    assert(res5.length == 2)
    res5.foreach(a => {
      assert(a.startOffset == 0)
      assert(a.endOffset == 8)
      assert(a.annotation == null)
    })
  }

  test("Test After(Search, FilterAnnotations)") {
    sc = new SparkContext(conf)

    val annotatedRDD = sc.parallelize(data)
    val annotatedSuccinctRDD = AnnotatedSuccinctRDD(annotatedRDD)

    // Check
    val query1 = After(Search("Document"), FilterAnnotations("ge", "word"))
    val res1 = annotatedSuccinctRDD.query(query1).collect()
    assert(res1.length == 0)

    val query2 = After(Search("number"), FilterAnnotations("ge", "word"))
    val res2 = annotatedSuccinctRDD.query(query2).collect()
    assert(res2.length == 3)
    res2.foreach(a => {
      assert(a.startOffset == 9)
      assert(a.endOffset == 15)
      assert(a.annotation == null)
    })

    val query3 = After(Search("number three"), FilterAnnotations("ge", "word"))
    val res3 = annotatedSuccinctRDD.query(query3).collect()
    assert(res3.length == 1)
    res3.foreach(a => {
      assert(a.startOffset == 9)
      assert(a.endOffset == 21)
      assert(a.annotation == null)
    })

    val query4 = After(Search("three"), FilterAnnotations("ge", "space"), 1)
    val res4 = annotatedSuccinctRDD.query(query4).collect()
    assert(res4.length == 1)
    res4.foreach(a => {
      assert(a.startOffset == 16)
      assert(a.endOffset == 21)
      assert(a.annotation == null)
    })

    val query5 = After(Search("three"), FilterAnnotations("ge", "word", _.nonEmpty, _.contains("cumen")))
    val res5 = annotatedSuccinctRDD.query(query5).collect()
    assert(res5.length == 1)
    res5.foreach(a => {
      assert(a.startOffset == 16)
      assert(a.endOffset == 21)
      assert(a.annotation == null)
    })
  }

  test("Test Contains(Search, Search)") {
    sc = new SparkContext(conf)

    val annotatedRDD = sc.parallelize(data)
    val annotatedSuccinctRDD = AnnotatedSuccinctRDD(annotatedRDD)

    // Check
    val query1 = Contains(Search("Document"), Search("cumen"))
    val res1 = annotatedSuccinctRDD.query(query1).collect()
    assert(res1.length == 3)
    res1.foreach(a => {
      assert(a.startOffset == 0)
      assert(a.endOffset == 8)
      assert(a.annotation == null)
    })

    val query2 = Contains(Search("number"), Search("um"))
    val res2 = annotatedSuccinctRDD.query(query2).collect()
    assert(res2.length == 3)
    res2.foreach(a => {
      assert(a.startOffset == 9)
      assert(a.endOffset == 15)
      assert(a.annotation == null)
    })

    val query3 = Contains(Search("number three"), Search("number"))
    val res3 = annotatedSuccinctRDD.query(query3).collect()
    assert(res3.length == 1)
    res3.foreach(a => {
      assert(a.startOffset == 9)
      assert(a.endOffset == 21)
      assert(a.annotation == null)
    })

    val query4 = Contains(Search("Document number"), Search(" "))
    val res4 = annotatedSuccinctRDD.query(query4).collect()
    assert(res4.length == 3)
    res4.foreach(a => {
      assert(a.startOffset == 0)
      assert(a.endOffset == 15)
      assert(a.annotation == null)
    })

    val query5 = Contains(Search("Document"), Search("number"))
    val res5 = annotatedSuccinctRDD.query(query5).collect()
    assert(res5.length == 0)
  }

  test("Test ContainedIn(Search, Search)") {
    sc = new SparkContext(conf)

    val annotatedRDD = sc.parallelize(data)
    val annotatedSuccinctRDD = AnnotatedSuccinctRDD(annotatedRDD)

    // Check
    val query1 = ContainedIn(Search("Document"), Search("Document number"))
    val res1 = annotatedSuccinctRDD.query(query1).collect()
    assert(res1.length == 3)
    res1.foreach(a => {
      assert(a.startOffset == 0)
      assert(a.endOffset == 8)
      assert(a.annotation == null)
    })

    val query2 = ContainedIn(Search("number"), Search("Document number"))
    val res2 = annotatedSuccinctRDD.query(query2).collect()
    assert(res2.length == 3)
    res2.foreach(a => {
      assert(a.startOffset == 9)
      assert(a.endOffset == 15)
      assert(a.annotation == null)
    })

    val query3 = ContainedIn(Search("three"), Search("number three"))
    val res3 = annotatedSuccinctRDD.query(query3).collect()
    assert(res3.length == 1)
    assert(res3(0).startOffset == 16)
    assert(res3(0).endOffset == 21)
    assert(res3(0).annotation == null)

    val query4 = ContainedIn(Search(" "), Search("Document number "))
    val res4 = annotatedSuccinctRDD.query(query4).collect()
    assert(res4.length == 6)
    res4.foreach(a => {
      assert(a.startOffset == 8 || a.startOffset == 15)
      assert(a.endOffset == 9 || a.endOffset == 16)
      assert(a.annotation == null)
    })

    val query5 = ContainedIn(Search("four"), Search("Document number three"))
    val res5 = annotatedSuccinctRDD.query(query5).collect()
    assert(res5.length == 0)
  }

  test("Test Before(Search, Search)") {
    sc = new SparkContext(conf)

    val annotatedRDD = sc.parallelize(data)
    val annotatedSuccinctRDD = AnnotatedSuccinctRDD(annotatedRDD)

    // Check
    val query1 = Before(Search("Document"), Search("number"))
    val res1 = annotatedSuccinctRDD.query(query1).collect()
    assert(res1.length == 3)
    res1.foreach(a => {
      assert(a.startOffset == 0)
      assert(a.endOffset == 8)
      assert(a.annotation == null)
    })

    val query2 = Before(Search("number"), Regex("one|two|three"))
    val res2 = annotatedSuccinctRDD.query(query2).collect()
    assert(res2.length == 3)
    res2.foreach(a => {
      assert(a.startOffset == 9)
      assert(a.endOffset == 15)
      assert(a.annotation == null)
    })

    val query3 = Before(Search("number three"), Search("Document"))
    val res3 = annotatedSuccinctRDD.query(query3).collect()
    assert(res3.length == 0)

    val query4 = Before(Search("Document"), Search(" "), 1)
    val res4 = annotatedSuccinctRDD.query(query4).collect()
    assert(res4.length == 3)
    res4.foreach(a => {
      assert(a.startOffset == 0)
      assert(a.endOffset == 8)
      assert(a.annotation == null)
    })
  }

  test("Test After(Search, Search)") {
    sc = new SparkContext(conf)

    val annotatedRDD = sc.parallelize(data)
    val annotatedSuccinctRDD = AnnotatedSuccinctRDD(annotatedRDD)

    // Check
    val query1 = After(Search("Document"), Search("number"))
    val res1 = annotatedSuccinctRDD.query(query1).collect()
    assert(res1.length == 0)

    val query2 = After(Search("number"), Search("Document"))
    val res2 = annotatedSuccinctRDD.query(query2).collect()
    assert(res2.length == 3)
    res2.foreach(a => {
      assert(a.startOffset == 9)
      assert(a.endOffset == 15)
      assert(a.annotation == null)
    })

    val query3 = After(Search("number three"), Search("Document"))
    val res3 = annotatedSuccinctRDD.query(query3).collect()
    assert(res3.length == 1)
    res3.foreach(a => {
      assert(a.startOffset == 9)
      assert(a.endOffset == 21)
      assert(a.annotation == null)
    })

    val query4 = After(Search("three"), Search(" "), 1)
    val res4 = annotatedSuccinctRDD.query(query4).collect()
    assert(res4.length == 1)
    res4.foreach(a => {
      assert(a.startOffset == 16)
      assert(a.endOffset == 21)
      assert(a.annotation == null)
    })
  }

  test("Test save and load") {
    sc = new SparkContext(conf)

    val annotatedRDD = sc.parallelize(data)
    val annotatedSuccinctRDD = AnnotatedSuccinctRDD(annotatedRDD)

    val tmpDir = Files.createTempDir()
    val succinctDir = tmpDir + "/succinct"
    annotatedSuccinctRDD.save(succinctDir)

    val reloadedRDD = AnnotatedSuccinctRDD(sc, succinctDir)

    val originalData = annotatedSuccinctRDD.collect()
    val newData = reloadedRDD.collect()

    assert(originalData === newData)
  }

  test("Test construct and load") {
    sc = new SparkContext(conf)

    val annotatedRDD = sc.parallelize(data)
    val tmpDir = Files.createTempDir()
    val succinctDir = tmpDir + "/succinct"
    AnnotatedSuccinctRDD.construct(annotatedRDD, succinctDir)

    val reloadedRDD = AnnotatedSuccinctRDD(sc, succinctDir)

    val originalData = AnnotatedSuccinctRDD(annotatedRDD).collect()
    val newData = reloadedRDD.collect()

    assert(originalData === newData)
  }
}
