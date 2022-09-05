package zio.schema

import java.math.{ BigInteger, MathContext }
import java.time.temporal.{ ChronoField, ChronoUnit }
import java.time.{
  DayOfWeek,
  Duration => JDuration,
  Instant,
  LocalDate,
  LocalDateTime,
  LocalTime,
  MonthDay,
  OffsetDateTime,
  OffsetTime,
  Period,
  Year,
  YearMonth,
  ZoneId,
  ZoneOffset,
  ZonedDateTime => JZonedDateTime
}

import scala.annotation.tailrec
import scala.collection.immutable.{ ListMap, Nil }

import zio.Chunk
import zio.schema.diff.Edit
import zio.schema.meta.Migration

sealed trait Patch[A] { self =>

  /**
   * A symbolic operator for [[zip]].
   */
  def <*>[B](that: Patch[B]): Patch[(A, B)] = self.zip(that)

  def zip[B](that: Patch[B]): Patch[(A, B)] = Patch.Tuple(self, that)

  def patch(a: A): Either[String, A]

  def unpatch(a: A): Either[String, A]

  def isIdentical: Boolean = false

  def isComparable: Boolean = true
}

object Patch {

  def identical[A]: Identical[A] = Identical()

  def notComparable[A]: NotComparable[A] = NotComparable()

  final case class Identical[A]() extends Patch[A] {
    override def patch(a: A): Either[String, A] = Right(a)
    override def isIdentical: Boolean           = true

    override def unpatch(a: A): Either[String, A] = Right(a)
  }

  final case class Bool(xor: Boolean) extends Patch[Boolean] {
    override def patch(a: Boolean): Either[String, Boolean] = Right(a ^ xor)

    override def unpatch(a: Boolean): Either[String, Boolean] = Right(a ^ xor)
  }

  final case class Number[A](distance: A)(implicit ev: Numeric[A]) extends Patch[A] {
    override def patch(input: A): Either[String, A] =
      Right(ev.minus(input, distance))

    override def unpatch(input: A): Either[String, A] = Right(ev.plus(input, distance))
  }

  final case class BigInt(distance: BigInteger) extends Patch[BigInteger] {
    override def patch(input: BigInteger): Either[String, BigInteger] =
      Right(input.subtract(distance))

    override def unpatch(input: BigInteger): Either[String, BigInteger] = Right(input.add(distance))
  }

  final case class BigDecimal(distance: java.math.BigDecimal, precision: Int) extends Patch[java.math.BigDecimal] {
    override def patch(input: java.math.BigDecimal): Either[String, java.math.BigDecimal] = {
      val mc = new MathContext(precision)
      Right(input.round(mc).subtract(distance, mc))
    }

    override def unpatch(input: java.math.BigDecimal): Either[String, java.math.BigDecimal] = {
      val mc = new MathContext(precision)
      Right(input.add(distance, mc))
    }
  }

  final case class Temporal[A](distances: List[Long], tpe: StandardType[A]) extends Patch[A] { self =>
    override def patch(a: A): Either[String, A] =
      (tpe, distances) match {
        case (_: StandardType.YearType.type, distance :: Nil) =>
          Right(Year.of(a.asInstanceOf[Year].getValue - distance.toInt).asInstanceOf[A])
        case (_: StandardType.YearMonthType.type, distance :: Nil) =>
          Right(
            YearMonth
              .now()
              .`with`(
                ChronoField.PROLEPTIC_MONTH,
                a.asInstanceOf[YearMonth].getLong(ChronoField.PROLEPTIC_MONTH) - distance
              )
              .asInstanceOf[A]
          )
        case (_: StandardType.LocalDateType, distance :: Nil) =>
          Right(LocalDate.ofEpochDay(a.asInstanceOf[LocalDate].toEpochDay - distance).asInstanceOf[A])
        case (_: StandardType.InstantType, dist1 :: dist2 :: Nil) =>
          Right(
            Instant
              .ofEpochSecond(a.asInstanceOf[Instant].getEpochSecond - dist1, a.asInstanceOf[Instant].getNano() - dist2)
              .asInstanceOf[A]
          )
        case (_: StandardType.LocalTimeType, distance :: Nil) =>
          Right(LocalTime.ofNanoOfDay(a.asInstanceOf[LocalTime].toNanoOfDay - distance).asInstanceOf[A])
        case (_: StandardType.LocalDateTimeType, dist1 :: dist2 :: Nil) =>
          Right {
            LocalDateTime
              .of(
                LocalDate.ofEpochDay(a.asInstanceOf[LocalDateTime].toLocalDate.toEpochDay - dist1),
                LocalTime.ofNanoOfDay(a.asInstanceOf[LocalDateTime].toLocalTime.toNanoOfDay - dist2)
              )
              .asInstanceOf[A]
          }
        case (_: StandardType.OffsetTimeType, dist1 :: dist2 :: Nil) =>
          Right {
            OffsetTime
              .of(
                LocalTime.ofNanoOfDay(a.asInstanceOf[OffsetTime].toLocalTime.toNanoOfDay - dist1),
                ZoneOffset.ofTotalSeconds(a.asInstanceOf[OffsetTime].getOffset.getTotalSeconds - dist2.toInt)
              )
              .asInstanceOf[A]
          }
        case (_: StandardType.OffsetDateTimeType, dist1 :: dist2 :: dist3 :: Nil) =>
          Right {
            OffsetDateTime
              .of(
                LocalDate.ofEpochDay(a.asInstanceOf[OffsetDateTime].toLocalDate.toEpochDay - dist1),
                LocalTime.ofNanoOfDay(a.asInstanceOf[OffsetDateTime].toLocalTime.toNanoOfDay - dist2),
                ZoneOffset.ofTotalSeconds(a.asInstanceOf[OffsetDateTime].getOffset.getTotalSeconds - dist3.toInt)
              )
              .asInstanceOf[A]
          }
        case (_: StandardType.PeriodType.type, dayAdjustment :: monthAdjustment :: yearAdjustment :: Nil) =>
          try {
            Right(
              Period
                .of(
                  a.asInstanceOf[Period].getYears - yearAdjustment.toInt,
                  a.asInstanceOf[Period].getMonths - monthAdjustment.toInt,
                  a.asInstanceOf[Period].getDays - dayAdjustment.toInt
                )
                .asInstanceOf[A]
            )
          } catch { case _: Throwable => Left(s"Invalid java.time.Period diff $self") }
        case (_: StandardType.ZoneOffsetType.type, distance :: Nil) =>
          try {
            Right(
              ZoneOffset.ofTotalSeconds(a.asInstanceOf[ZoneOffset].getTotalSeconds + distance.toInt).asInstanceOf[A]
            )
          } catch { case t: Throwable => Left(s"Patched offset is invalid: ${t.getMessage}") }
        case (_: StandardType.DayOfWeekType.type, distance :: Nil) =>
          Right(a.asInstanceOf[DayOfWeek].plus(distance).asInstanceOf[A])
        case (_: StandardType.MonthType.type, distance :: Nil) =>
          Right(a.asInstanceOf[java.time.Month].plus(distance).asInstanceOf[A])
        case (_: StandardType.DurationType.type, dist1 :: dist2 :: Nil) =>
          Right(
            JDuration
              .ofSeconds(a.asInstanceOf[JDuration].getSeconds - dist1, a.asInstanceOf[JDuration].getNano() - dist2)
              .asInstanceOf[A]
          )
        //      // TODO need to deal with leap year differences
        case (_: StandardType.MonthDayType.type, regDiff :: _ :: Nil) =>
          Right(
            MonthDay.from(ChronoUnit.DAYS.addTo(a.asInstanceOf[MonthDay].atYear(2001), regDiff.toLong)).asInstanceOf[A]
          )
        case (s, _) => Left(s"Cannot apply temporal diff to value with type $s")
      }

    override def unpatch(a: A): Either[String, A] = (tpe, distances) match {
      case (_: StandardType.YearType.type, distance :: Nil) =>
        Right(Year.of(a.asInstanceOf[Year].getValue + distance.toInt).asInstanceOf[A])
      case (_: StandardType.YearMonthType.type, distance :: Nil) =>
        Right(
          YearMonth
            .now()
            .`with`(
              ChronoField.PROLEPTIC_MONTH,
              a.asInstanceOf[YearMonth].getLong(ChronoField.PROLEPTIC_MONTH) + distance
            )
            .asInstanceOf[A]
        )
      case (_: StandardType.LocalDateType, distance :: Nil) =>
        Right(LocalDate.ofEpochDay(a.asInstanceOf[LocalDate].toEpochDay + distance).asInstanceOf[A])
      case (_: StandardType.InstantType, dist1 :: dist2 :: Nil) =>
        Right(
          Instant
            .ofEpochSecond(a.asInstanceOf[Instant].getEpochSecond + dist1, a.asInstanceOf[Instant].getNano() + dist2)
            .asInstanceOf[A]
        )
      case (_: StandardType.LocalTimeType, distance :: Nil) =>
        Right(LocalTime.ofNanoOfDay(a.asInstanceOf[LocalTime].toNanoOfDay + distance).asInstanceOf[A])
      case (_: StandardType.LocalDateTimeType, dist1 :: dist2 :: Nil) =>
        Right {
          LocalDateTime
            .of(
              LocalDate.ofEpochDay(a.asInstanceOf[LocalDateTime].toLocalDate.toEpochDay + dist1),
              LocalTime.ofNanoOfDay(a.asInstanceOf[LocalDateTime].toLocalTime.toNanoOfDay + dist2)
            )
            .asInstanceOf[A]
        }
      case (_: StandardType.OffsetTimeType, dist1 :: dist2 :: Nil) =>
        Right {
          OffsetTime
            .of(
              LocalTime.ofNanoOfDay(a.asInstanceOf[OffsetTime].toLocalTime.toNanoOfDay + dist1),
              ZoneOffset.ofTotalSeconds(a.asInstanceOf[OffsetTime].getOffset.getTotalSeconds + dist2.toInt)
            )
            .asInstanceOf[A]
        }
      case (_: StandardType.OffsetDateTimeType, dist1 :: dist2 :: dist3 :: Nil) =>
        Right {
          OffsetDateTime
            .of(
              LocalDate.ofEpochDay(a.asInstanceOf[OffsetDateTime].toLocalDate.toEpochDay + dist1),
              LocalTime.ofNanoOfDay(a.asInstanceOf[OffsetDateTime].toLocalTime.toNanoOfDay + dist2),
              ZoneOffset.ofTotalSeconds(a.asInstanceOf[OffsetDateTime].getOffset.getTotalSeconds + dist3.toInt)
            )
            .asInstanceOf[A]
        }
      case (_: StandardType.PeriodType.type, dayAdjustment :: monthAdjustment :: yearAdjustment :: Nil) =>
        try {
          Right(
            Period
              .of(
                a.asInstanceOf[Period].getYears + yearAdjustment.toInt,
                a.asInstanceOf[Period].getMonths + monthAdjustment.toInt,
                a.asInstanceOf[Period].getDays + dayAdjustment.toInt
              )
              .asInstanceOf[A]
          )
        } catch {
          case _: Throwable => Left(s"Invalid java.time.Period diff $self")
        }
      case (_: StandardType.ZoneOffsetType.type, distance :: Nil) =>
        try {
          Right(
            ZoneOffset.ofTotalSeconds(a.asInstanceOf[ZoneOffset].getTotalSeconds - distance.toInt).asInstanceOf[A]
          )
        } catch {
          case t: Throwable => Left(s"Patched offset is invalid: ${t.getMessage}")
        }
      case (_: StandardType.DayOfWeekType.type, distance :: Nil) =>
        Right(a.asInstanceOf[DayOfWeek].minus(distance).asInstanceOf[A])
      case (_: StandardType.MonthType.type, distance :: Nil) =>
        Right(a.asInstanceOf[java.time.Month].minus(distance).asInstanceOf[A])
      case (_: StandardType.DurationType.type, dist1 :: dist2 :: Nil) =>
        Right(
          JDuration
            .ofSeconds(a.asInstanceOf[JDuration].getSeconds + dist1, a.asInstanceOf[JDuration].getNano() + dist2)
            .asInstanceOf[A]
        )
      //      // TODO need to deal with leap year differences
      case (_: StandardType.MonthDayType.type, regDiff :: _ :: Nil) =>
        Right(
          MonthDay.from(ChronoUnit.DAYS.addTo(a.asInstanceOf[MonthDay].atYear(2001), -regDiff.toLong)).asInstanceOf[A]
        )
      case (s, _) => Left(s"Cannot apply temporal diff to value with type $s")
    }
  }

  final case class ZonedDateTime(localDateTimeDiff: Patch[java.time.LocalDateTime], zoneIdDiff: Patch[String])
      extends Patch[java.time.ZonedDateTime] {
    override def patch(input: JZonedDateTime): scala.Either[String, JZonedDateTime] =
      for {
        patchedLocalDateTime <- localDateTimeDiff.patch(input.toLocalDateTime)
        patchedZoneId        <- zoneIdDiff.patch(input.getZone.getId)
        patched <- try {
                    Right(JZonedDateTime.of(patchedLocalDateTime, ZoneId.of(patchedZoneId)))
                  } catch {
                    case e: Throwable =>
                      Left(
                        s"Patched ZonedDateTime is not valid. Patched values $patchedLocalDateTime, $patchedZoneId. Error=${e.getMessage}"
                      )
                  }
      } yield patched

    override def unpatch(input: JZonedDateTime): Either[String, JZonedDateTime] =
      for {
        unpatchedLocalDateTime <- localDateTimeDiff.unpatch(input.toLocalDateTime)
        unpatchedZoneId        <- zoneIdDiff.unpatch(input.getZone.getId)
        patched <- try {
                    Right(JZonedDateTime.of(unpatchedLocalDateTime, ZoneId.of(unpatchedZoneId)))
                  } catch {
                    case e: Throwable =>
                      Left(
                        s"Patched ZonedDateTime is not valid. Un-patched values $unpatchedLocalDateTime, $unpatchedZoneId. Error=${e.getMessage}"
                      )
                  }
      } yield patched
  }

  final case class Tuple[A, B](leftDifference: Patch[A], rightDifference: Patch[B]) extends Patch[(A, B)] {

    override def isIdentical: Boolean = leftDifference.isIdentical && rightDifference.isIdentical
    override def patch(input: (A, B)): Either[String, (A, B)] =
      for {
        l <- leftDifference.patch(input._1)
        r <- rightDifference.patch(input._2)
      } yield (l, r)

    override def unpatch(input: (A, B)): Either[String, (A, B)] =
      for {
        l <- leftDifference.unpatch(input._1)
        r <- rightDifference.unpatch(input._2)
      } yield (l, r)
  }

  final case class LCS[A](edits: Chunk[Edit[A]]) extends Patch[Chunk[A]] {
    import zio.schema.diff.{ Edit => ZEdit }

    @tailrec
    private def calc(in: List[A], edits: List[Edit[A]], result: List[A]): Either[String, Chunk[A]] = (in, edits) match {
      case (_ :: _, Nil)                            => Left(s"Incorrect Diff - no instructions for these items: ${in.mkString}.")
      case (h :: _, ZEdit.Delete(s) :: _) if s != h => Left(s"Cannot Delete $s - current letter is $h.")
      case (Nil, ZEdit.Delete(s) :: _)              => Left(s"Cannot Delete $s - no items left to delete.")
      case (_ :: t, ZEdit.Delete(_) :: tail)        => calc(t, tail, result)
      case (h :: _, ZEdit.Keep(s) :: _) if s != h   => Left(s"Cannot Keep $s - current letter is $h.")
      case (Nil, ZEdit.Keep(s) :: _)                => Left(s"Cannot Keep $s - no items left to keep.")
      case (h :: t, ZEdit.Keep(_) :: tail)          => calc(t, tail, result :+ h)
      case (in, ZEdit.Insert(s) :: tail)            => calc(in, tail, result :+ s)
      case (Nil, Nil)                               => Right(Chunk.fromIterable(result))
    }
    override def patch(as: Chunk[A]): Either[String, Chunk[A]] = calc(as.toList, edits.toList, Nil)

    override def unpatch(as: Chunk[A]): Either[String, Chunk[A]] = {

      val inverted = edits.map {
        case Edit.Insert(value) => Edit.Delete(value)
        case Edit.Delete(value) => Edit.Insert(value)
        case Edit.Keep(value)   => Edit.Keep(value)
      }
      calc(as.toList, inverted.toList, Nil)
    }
  }

  final case class Total[A](value: A) extends Patch[A] {
    override def patch(input: A): Either[String, A] = Right(value)

    override def unpatch(input: A): Either[String, A] = Right(value)
  }

  final case class EitherDiff[A, B](diff: Either[Patch[A], Patch[B]]) extends Patch[Either[A, B]] {
    override def isIdentical: Boolean = diff.fold(_.isIdentical, _.isIdentical)

    override def isComparable: Boolean = diff.fold(_.isComparable, _.isComparable)

    override def patch(input: Either[A, B]): Either[String, Either[A, B]] = (input, diff) match {
      case (Left(_), Right(_)) => Left(s"Cannot apply a right diff to a left value")
      case (Right(_), Left(_)) => Left(s"Cannot apply a left diff to a right value")
      case (Left(in), Left(diff)) =>
        diff.patch(in).map(Left(_))
      case (Right(in), Right(diff)) =>
        diff.patch(in).map(Right(_))
    }

    override def unpatch(input: Either[A, B]): Either[String, Either[A, B]] = (input, diff) match {
      case (Left(_), Right(_)) => Left(s"Cannot apply a right diff to a left value")
      case (Right(_), Left(_)) => Left(s"Cannot apply a left diff to a right value")
      case (Left(in), Left(diff)) =>
        diff.unpatch(in).map(Left(_))
      case (Right(in), Right(diff)) =>
        diff.unpatch(in).map(Right(_))
    }
  }

  final case class Transform[A, B](diff: Patch[A], f: A => Either[String, B], g: B => Either[String, A])
      extends Patch[B] {
    override def isIdentical: Boolean = diff.isIdentical

    override def isComparable: Boolean = diff.isComparable

    override def patch(input: B): Either[String, B] =
      for {
        a  <- g(input)
        a1 <- diff.patch(a)
        b  <- f(a1)
      } yield b

    override def unpatch(input: B): Either[String, B] =
      for {
        a  <- g(input)
        a1 <- diff.unpatch(a)
        b  <- f(a1)
      } yield b
  }

  /**
   * Represents diff between incomparable values. For instance Left(1) and Right("a")
   */
  final case class NotComparable[A]() extends Patch[A] {
    override def patch(input: A): Either[String, A] =
      Left(s"Non-comparable diff cannot be applied")

    override def isComparable: Boolean = false

    override def unpatch(a: A): Either[String, A] =
      Left(s"Non-comparable diff cannot be applied")
  }

  final case class SchemaMigration(migrations: Chunk[Migration]) extends Patch[Schema[_]] { self =>

    //TODO Probably need to implement this
    override def patch(input: Schema[_]): Either[String, Schema[_]] = Left(s"Schema migrations cannot be applied")

    def orIdentical: Patch[Schema[_]] =
      if (migrations.isEmpty) Patch.identical
      else self

    override def unpatch(a: Schema[_]): Either[String, Schema[_]] = Left(s"Schema migrations cannot be applied")
  }

  /**
   * Map of field-level diffs between two records. The map of differences
   * is keyed to the records field names.
   */
  final case class Record[R](differences: ListMap[String, Patch[_]], schema: Schema.Record[R]) extends Patch[R] {
    self =>
    override def isIdentical: Boolean = differences.forall(_._2.isIdentical)

    override def isComparable: Boolean = differences.forall(_._2.isComparable)

    override def patch(input: R): Either[String, R] = {
      val structure = schema.structure

      val patchedDynamicValue = schema.toDynamic(input) match {
        case DynamicValue.Record(values) =>
          differences.foldLeft[Either[String, ListMap[String, DynamicValue]]](Right(values)) {
            case (Right(record), (key, diff)) =>
              (structure.find(_.label == key).map(_.schema), values.get(key)) match {
                case (Some(schema: Schema[b]), Some(oldValue)) =>
                  val oldVal = oldValue.toTypedValue(schema)
                  oldVal
                    .flatMap(v => diff.asInstanceOf[Patch[Any]].patch(v))
                    .map(v => schema.asInstanceOf[Schema[Any]].toDynamic(v)) match {
                    case Left(error)     => Left(error)
                    case Right(newValue) => Right(record + (key -> newValue))
                  }
                case _ =>
                  Left(s"Values=$values and structure=$structure have incompatible shape.")
              }
            case (Left(string), _) => Left(string)
          }
        case dv => Left(s"Failed to apply record diff. Unexpected dynamic value for record: $dv")
      }

      patchedDynamicValue.flatMap { newValues =>
        schema.fromDynamic(DynamicValue.Record(newValues))
      }
    }

    def orIdentical: Patch[R] =
      if (differences.values.forall(_.isIdentical))
        Patch.identical
      else
        self

    override def unpatch(input: R): Either[String, R] = {

      val structure = schema.structure

      val unpatchedDynamicValue = schema.toDynamic(input) match {
        case DynamicValue.Record(values) =>
          differences.foldLeft[Either[String, ListMap[String, DynamicValue]]](Right(values)) {
            case (Right(record), (key, diff)) =>
              (structure.find(_.label == key).map(_.schema), values.get(key)) match {
                case (Some(schema: Schema[b]), Some(oldValue)) =>
                  val oldVal = oldValue.toTypedValue(schema)
                  oldVal
                    .flatMap(v => diff.asInstanceOf[Patch[Any]].unpatch(v))
                    .map(v => schema.asInstanceOf[Schema[Any]].toDynamic(v)) match {
                    case Left(error)     => Left(error)
                    case Right(newValue) => Right(record + (key -> newValue))
                  }
                case _ =>
                  Left(s"Values=$values and structure=$structure have incompatible shape.")
              }
            case (Left(string), _) => Left(string)
          }
        case dv => Left(s"Failed to apply record diff. Unexpected dynamic value for record: $dv")
      }

      unpatchedDynamicValue.flatMap { newValues =>
        schema.fromDynamic(DynamicValue.Record(newValues))
      }
    }

  }

}
