/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.util;

import org.apache.calcite.avatica.util.DateTimeUtils;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;

import static com.google.common.base.Preconditions.checkArgument;

import static org.apache.calcite.avatica.util.DateTimeUtils.DATE_FORMAT_STRING;
import static org.apache.calcite.avatica.util.DateTimeUtils.TIMESTAMP_FORMAT_STRING;
import static org.apache.calcite.util.DateTimeStringUtils.getDateFormatter;
import static org.apache.calcite.util.Static.RESOURCE;

import static java.lang.Math.floorMod;
import static java.util.Objects.requireNonNull;

/**
 * Timestamp with time-zone literal.
 *
 * <p>Immutable, internally represented as a string (in ISO format),
 * and can support unlimited precision (milliseconds, nanoseconds).
 */
public class TimestampWithTimeZoneString
    implements Comparable<TimestampWithTimeZoneString> {

  final TimestampString localDateTime;
  final TimeZone timeZone;
  final String v;
  private final DateTimeUtils.PrecisionTime pt;

  private static final ThreadLocal<@Nullable SimpleDateFormat> TIMESTAMP_FORMAT =
      ThreadLocal.withInitial(() ->
          getDateFormatter(TIMESTAMP_FORMAT_STRING));

  /** Creates a TimestampWithTimeZoneString. */
  public TimestampWithTimeZoneString(TimestampString localDateTime, TimeZone timeZone) {
    this.localDateTime = localDateTime;
    this.timeZone = timeZone;
    this.v = localDateTime.toString() + " " + timeZone.getID();

    this.pt = parseDateTime(localDateTime.toString(), this.timeZone);
  }

  /** Creates a TimestampWithTimeZoneString. */
  public TimestampWithTimeZoneString(String v) {
    // search next space after "yyyy-MM-dd "
    int pos = v.indexOf(' ', DATE_FORMAT_STRING.length() + 1);

    if (pos == -1) {
      throw RESOURCE.illegalLiteral("TIMESTAMP WITH LOCAL TIME ZONE", v,
              RESOURCE.badFormat(TIMESTAMP_FORMAT_STRING).str()).ex();
    }

    String tsStr = v.substring(0, pos);
    this.localDateTime = new TimestampString(tsStr);

    String timeZoneString = v.substring(v.indexOf(' ', DATE_FORMAT_STRING.length() + 1) + 1);
    checkArgument(DateTimeStringUtils.isValidTimeZone(timeZoneString));
    this.timeZone = TimeZone.getTimeZone(timeZoneString);
    this.v = v;
    this.pt = parseDateTime(tsStr, this.timeZone);
  }

  /** Creates a TimestampWithTimeZoneString for year, month, day, hour, minute,
   * second, millisecond values in the given time-zone. */
  public TimestampWithTimeZoneString(int year, int month, int day, int h, int m, int s,
      String timeZone) {
    this(DateTimeStringUtils.ymdhms(new StringBuilder(), year, month, day, h, m, s).toString()
        + " " + timeZone);
  }

  /** Sets the fraction field of a {@code TimestampWithTimeZoneString} to a given number
   * of milliseconds. Nukes the value set via {@link #withNanos}.
   *
   * <p>For example,
   * {@code new TimestampWithTimeZoneString(1970, 1, 1, 2, 3, 4, "GMT").withMillis(56)}
   * yields {@code TIMESTAMP WITH LOCAL TIME ZONE '1970-01-01 02:03:04.056 GMT'}. */
  public TimestampWithTimeZoneString withMillis(int millis) {
    checkArgument(millis >= 0 && millis < 1000);
    return withFraction(DateTimeStringUtils.pad(3, millis));
  }

  /** Sets the fraction field of a {@code TimestampWithTimeZoneString} to a given number
   * of nanoseconds. Nukes the value set via {@link #withMillis(int)}.
   *
   * <p>For example,
   * {@code new TimestampWithTimeZoneString(1970, 1, 1, 2, 3, 4, "GMT").withNanos(56789)}
   * yields {@code TIMESTAMP WITH LOCAL TIME ZONE '1970-01-01 02:03:04.000056789 GMT'}. */
  public TimestampWithTimeZoneString withNanos(int nanos) {
    checkArgument(nanos >= 0 && nanos < 1000000000);
    return withFraction(DateTimeStringUtils.pad(9, nanos));
  }

  /** Sets the fraction field of a {@code TimestampString}.
   * The precision is determined by the number of leading zeros.
   * Trailing zeros are stripped.
   *
   * <p>For example, {@code
   * new TimestampWithTimeZoneString(1970, 1, 1, 2, 3, 4, "GMT").withFraction("00506000")}
   * yields {@code TIMESTAMP WITH LOCAL TIME ZONE '1970-01-01 02:03:04.00506 GMT'}. */
  public TimestampWithTimeZoneString withFraction(String fraction) {
    return new TimestampWithTimeZoneString(
        localDateTime.withFraction(fraction), timeZone);
  }

  /** Creates a TimestampWithTimeZoneString from a Calendar. */
  public static TimestampWithTimeZoneString fromCalendarFields(Calendar calendar) {
    TimestampString ts = TimestampString.fromCalendarFields(calendar);
    return new TimestampWithTimeZoneString(ts, calendar.getTimeZone());
  }

  public TimestampWithTimeZoneString withTimeZone(TimeZone timeZone) {
    if (this.timeZone.equals(timeZone)) {
      return this;
    }
    String fraction = pt.getFraction();

    pt.getCalendar().setTimeZone(timeZone);
    if (!fraction.isEmpty()) {
      return new TimestampWithTimeZoneString(
          pt.getCalendar().get(Calendar.YEAR),
          pt.getCalendar().get(Calendar.MONTH) + 1,
          pt.getCalendar().get(Calendar.DAY_OF_MONTH),
          pt.getCalendar().get(Calendar.HOUR_OF_DAY),
          pt.getCalendar().get(Calendar.MINUTE),
          pt.getCalendar().get(Calendar.SECOND),
          timeZone.getID())
              .withFraction(fraction);
    }
    return new TimestampWithTimeZoneString(
        pt.getCalendar().get(Calendar.YEAR),
        pt.getCalendar().get(Calendar.MONTH) + 1,
        pt.getCalendar().get(Calendar.DAY_OF_MONTH),
        pt.getCalendar().get(Calendar.HOUR_OF_DAY),
        pt.getCalendar().get(Calendar.MINUTE),
        pt.getCalendar().get(Calendar.SECOND),
        timeZone.getID());
  }

  @Override public String toString() {
    return v;
  }

  @Override public boolean equals(@Nullable Object o) {
    // The value is in canonical form (no trailing zeros).
    return o == this
        || o instanceof TimestampWithTimeZoneString
        && ((TimestampWithTimeZoneString) o).v.equals(v);
  }

  @Override public int hashCode() {
    return v.hashCode();
  }

  @Override public int compareTo(TimestampWithTimeZoneString o) {
    return v.compareTo(o.v);
  }

  public TimestampWithTimeZoneString round(int precision) {
    checkArgument(precision >= 0);
    return new TimestampWithTimeZoneString(
        localDateTime.round(precision), timeZone);
  }

  /** Creates a TimestampWithTimeZoneString that is a given number of milliseconds since
   * the epoch UTC. */
  public static TimestampWithTimeZoneString fromMillisSinceEpoch(long millis) {
    return new TimestampWithTimeZoneString(
        DateTimeUtils.unixTimestampToString(millis) + " " + DateTimeUtils.UTC_ZONE.getID())
            .withMillis((int) floorMod(millis, 1000L));
  }

  /** Converts this TimestampWithTimeZoneString to a string, truncated or padded with
   * zeros to a given precision. */
  public String toString(int precision) {
    checkArgument(precision >= 0);
    return localDateTime.toString(precision) + " " + timeZone.getID();
  }

  public DateString getLocalDateString() {
    return new DateString(localDateTime.toString().substring(0, 10));
  }

  public TimeString getLocalTimeString() {
    return new TimeString(localDateTime.toString().substring(11));
  }

  public TimestampString getLocalTimestampString() {
    return localDateTime;
  }

  public TimeZone getTimeZone() {
    return timeZone;
  }

  private static DateTimeUtils.PrecisionTime parseDateTime(String tsStr, TimeZone timeZone) {
    DateTimeUtils.PrecisionTime pt =
        DateTimeUtils.parsePrecisionDateTimeLiteral(tsStr, requireNonNull(TIMESTAMP_FORMAT.get()),
            timeZone, -1);

    if (pt == null) {
      throw RESOURCE.illegalLiteral("TIMESTAMP WITH LOCAL TIME ZONE", tsStr,
          RESOURCE.badFormat(TIMESTAMP_FORMAT_STRING).str()).ex();
    }

    return pt;
  }
}
