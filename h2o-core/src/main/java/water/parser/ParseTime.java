package water.parser;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.DateTimeFormatterBuilder;
import water.util.Log;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public abstract class ParseTime {
  // Deduce if we are looking at a Date/Time value, or not.
  // If so, return time as msec since Jan 1, 1970 or Long.MIN_VALUE.

  // I tried java.util.SimpleDateFormat, but it just throws too many
  // exceptions, including ParseException, NumberFormatException, and
  // ArrayIndexOutOfBoundsException... and the Piece de resistance: a
  // ClassCastException deep in the SimpleDateFormat code:
  // "sun.util.calendar.Gregorian$Date cannot be cast to sun.util.calendar.JulianCalendar$Date"

  public static boolean isTime(BufferedString str) {
    return attemptTimeParse(str) != Long.MIN_VALUE;
  }

  private static final byte MMS[][][] = new byte[][][] {
    {"jan".getBytes(),"january"  .getBytes()},
    {"feb".getBytes(),"february" .getBytes()},
    {"mar".getBytes(),"march"    .getBytes()},
    {"apr".getBytes(),"april"    .getBytes()},
    {"may".getBytes(),"may"      .getBytes()},
    {"jun".getBytes(),"june"     .getBytes()},
    {"jul".getBytes(),"july"     .getBytes()},
    {"aug".getBytes(),"august"   .getBytes()},
    {"sep".getBytes(),"september".getBytes()},
    {"oct".getBytes(),"october"  .getBytes()},
    {"nov".getBytes(),"november" .getBytes()},
    {"dec".getBytes(),"december" .getBytes()}
  };

  public static long attemptTimeParse( BufferedString str ) {
    try {
      long t0 = attemptYearFirstTimeParse(str); // "yyyy-MM-dd" and time if present
      if( t0 != Long.MIN_VALUE ) return t0;
      long t1 = attemptDayFirstTimeParse1(str); // "dd-MMM-yy" and time if present
      if( t1 != Long.MIN_VALUE ) return t1;
      long t2 = attemptYearMonthTimeParse(str); // "yy-MMM", not ambiguous with dd-MMM-yy because of trailing "-yy"
      if( t2 != Long.MIN_VALUE ) return t2;
      long t3 = attemptTimeOnlyParse(str); // Time if present, no date
      if( t3 != Long.MIN_VALUE ) return t3;
      long t4 = attemptDayFirstTimeParse2(str); // "dd/MM/yy" and time if present; note that this format is ambiguous
      if( t4 != Long.MIN_VALUE ) return t4;     // Cant tell which date: 3/2/10 is
    } catch( org.joda.time.IllegalFieldValueException | // Not time at all
             org.joda.time.IllegalInstantException      // Parsed as time, but falls into e.g. a daylight-savings hour hole
             ie ) { } //FIXME should collect errors and report at end of parse
    return Long.MIN_VALUE;
  }
  // Tries to parse "yyyy-MM[-dd] [HH:mm:ss.SSS aa]"
  // Tries to parse "yyyyMMdd-HH:mm:ss.SSS aa".  In this form the dash and trailing time is required
  private static long attemptYearFirstTimeParse(BufferedString str) {
    final byte[] buf = str.getBuffer();
    int i=str.getOffset();
    final int end = i+str.length();
    while( i < end && buf[i] == ' ' ) i++;
    if   ( i < end && buf[i] == '"' ) i++;
    if( (end-i) < 6 ) return Long.MIN_VALUE;
    int yyyy=0, MM=0, dd=0;

    // Parse date
    yyyy = digit(yyyy,buf[i++]);
    yyyy = digit(yyyy,buf[i++]);
    yyyy = digit(yyyy,buf[i++]);
    yyyy = digit(yyyy,buf[i++]);
    final boolean dash = buf[i] == '-';
    if( dash ) i++;
    MM = digit(MM,buf[i++]);
    // note: at this point we need guard every increment of "i" to avoid reaching outside of the buffer
    MM = i<end && buf[i]!='-' ? digit(MM,buf[i++]) : MM;
    if( MM < 1 || MM > 12 ) return Long.MIN_VALUE;
    if( (end-i)>=2 ) {
      if( dash && buf[i++] != '-' ) return Long.MIN_VALUE;
      dd = digit(dd, buf[i++]);
      dd = i < end && buf[i] >= '0' && buf[i] <= '9' ? digit(dd, buf[i++]) : dd;
      if( dd < 1 || dd > 31 ) return Long.MIN_VALUE;
    } else {
      if( !dash ) return Long.MIN_VALUE; // yyyyMM is ambiguous with plain numbers
      dd=1;                              // yyyy-MM; no day
    }
    if( dash ) {                // yyyy-MM[-dd]
      while( i < end && buf[i] == ' ' ) i++; // optional seperator or trailing blanks
      if( i==end )
        return new DateTime(yyyy,MM,dd,0,0,0, getTimezone()).getMillis();
    } else {                    // yyyyMMdd-HH:mm:ss.SSS; dash AND time is now required
      if( i==end || buf[i++] != '-' ) return Long.MIN_VALUE;
    }

    //Parse time
    return parseTime(buf, i, end, yyyy, MM, dd, false);
  }

  // Tries to parse "[dd[-]]MMM[-]yy[yy][:' '][HH:mm:ss.SSS aa]" where MMM is a
  // text representation of the month (e.g. Jul or July).  Day is optional.
  private static long attemptDayFirstTimeParse1(BufferedString str) {
    final byte[] buf = str.getBuffer();
    int i=str.getOffset();
    final int end = i+str.length();
    while( i < end && buf[i] == ' ' ) i++;
    if   ( i < end && buf[i] == '"' ) i++;
    if( (end-i) < 5 ) return Long.MIN_VALUE;
    int yyyy=0, MM=0, dd=0;

    // Parse day
    if( isDigit(buf[i]) ) {
      dd = digit(dd,buf[i++]);
      if( isDigit(buf[i]) ) dd = digit(dd,buf[i++]);
      if( dd < 1 || dd > 31 ) return Long.MIN_VALUE;
      if( buf[i] == '-' ) i++;
    } else dd = 1;              // No date, assume 1st
    if( !isChar(buf[i]) ) return Long.MIN_VALUE;

    // Parse month
    MM = parseMonth(buf,i,end);
    if( MM == -1 ) return Long.MIN_VALUE; // No matching month
    i += (MM>>4);               // Skip parsed month bytes
    MM &= 0xF;                  // 1-based month in low nybble
    if( end-i>=1 && buf[i] == '-' ) i++;
    if( end-i < 2 ) return Long.MIN_VALUE;

    // Parse year
    yyyy = digit(yyyy,buf[i++]);    // 2-digit year
    yyyy = digit(yyyy,buf[i++]);
    if( end-i>=2 && buf[i] != '"' && buf[i] != ' ' && buf[i] != ':') {
      yyyy = digit(yyyy,buf[i++]);  // 4-digit year
      yyyy = digit(yyyy,buf[i++]);
    } else { //POSIX 2004 & 2008 says 69-99 -> 1900s, 00-68 -> 2000s
      yyyy += (yyyy >= 69) ? 1900 : 2000;
    }
    while( i<end && buf[i] == ' ' ) i++;
    if( i<end && buf[i] == '"' ) i++;
    if( i==end )
      return new DateTime(yyyy,MM,dd,0,0,0, getTimezone()).getMillis();

    // Parse time
    if( buf[i] == ':') i++;
    return parseTime(buf, i, end, yyyy, MM, dd, false);
  }

  // Tries to parse "MM/dd/yy[yy][:' '][HH:mm:ss.SSS aa]" where MM is a value
  // from 1 to 12, and the separator is required.  Note that this is ambiguous
  // and is defaulting to American, not European time.  Example: 3/2/10 parses
  // as March 2, 2010 and NOT February 3, 2010.
  private static long attemptDayFirstTimeParse2(BufferedString str) {
    final byte[] buf = str.getBuffer();
    int i=str.getOffset();
    final int end = i+str.length();
    while( i < end && buf[i] == ' ' ) i++;
    if   ( i < end && buf[i] == '"' ) i++;
    if( (end-i) < 6 ) return Long.MIN_VALUE;
    int yyyy=0, MM=0, dd=0;

    // Parse date
    MM = digit(MM,buf[i++]);
    if( isDigit(buf[i]) ) MM = digit(MM,buf[i++]);
    if( MM < 1 || MM > 12 ) return Long.MIN_VALUE;
    byte sep = buf[i++];
    if( sep != '-' && sep != '/' ) return Long.MIN_VALUE;
    dd = digit(dd,buf[i++]);
    if( isDigit(buf[i]) ) dd = digit(dd,buf[i++]);
    if( dd < 1 || dd > 31 ) return Long.MIN_VALUE;
    if( sep != buf[i++] ) return Long.MIN_VALUE;
    yyyy = digit(yyyy,buf[i++]);    // 2-digit year
    yyyy = digit(yyyy,buf[i++]);
    if( end-i>=2 && isDigit(buf[i]) ) {
      yyyy = digit(yyyy,buf[i++]);  // 4-digit year
      yyyy = digit(yyyy,buf[i++]);
    } else { //POSIX 2004 & 2008 says 69-99 -> 1900s, 00-68 -> 2000s
      yyyy += (yyyy >= 69) ? 1900 : 2000;
    }
    while( i<end && buf[i] == ' ' ) i++;
    if( i<end && buf[i] == '"' ) i++;
    if( i==end )
      return new DateTime(yyyy,MM,dd,0,0,0, getTimezone()).getMillis();

    // Parse time
    if( buf[i] == ':') i++;
    return parseTime(buf, i, end, yyyy, MM, dd, false);
  }

  // Tries to parse "yy-MMM".  Note that this is not ambiguous with dd-MMM-yy
  // which requires a trailing "-yy" year.
  private static long attemptYearMonthTimeParse(BufferedString str) {
    final byte[] buf = str.getBuffer();
    int i=str.getOffset();
    final int end = i+str.length();
    while( i < end && buf[i] == ' ' ) i++;
    if   ( i < end && buf[i] == '"' ) i++;
    if( (end-i) < 6 ) return Long.MIN_VALUE;
    int yyyy=0, MM=0;

    // Parse year
    yyyy = digit(yyyy,buf[i++]);
    yyyy = digit(yyyy,buf[i++]);
    if( buf[i++] != '-' ) return Long.MIN_VALUE;
    yyyy += (yyyy >= 69) ? 1900 : 2000; //POSIX 2004 & 2008 says 69-99 -> 1900s, 00-68 -> 2000s

    // Parse month
    MM = parseMonth(buf,i,end);
    if( MM == -1 ) return Long.MIN_VALUE; // No matching month
    i += (MM>>4);               // Skip parsed month bytes
    MM &= 0xF;                  // 1-based month in low nybble
    while( i < end && buf[i] == ' ' ) i++;
    if( i==end ) return new DateTime(yyyy,MM,1,0,0,0, getTimezone()).getMillis();
    return Long.MIN_VALUE;      // Something odd
  }

  // Tries to parse time without any date.
  private static long attemptTimeOnlyParse(BufferedString str) {
    final byte[] buf = str.getBuffer();
    int i=str.getOffset();
    final int end = i+str.length();
    while( i < end && buf[i] == ' ' ) i++;
    if   ( i < end && buf[i] == '"' ) i++;
    if( end-i < 5 ) return Long.MIN_VALUE;
    long t1 = parseTime(buf,i,end,1970,1,1,true); // Unix Epoch dates
    if( t1 == Long.MIN_VALUE ) return Long.MIN_VALUE;
    // Remove all TZ info; return bare msec from the morning of the epoch
    return t1+getTimezone().getOffsetFromLocal(t1);
  }

  /** Parse textual (not numeric) month
   * @param buf - byte buffer containing text to parse
   * @param i - index of expected start of time string in buffer
   * @return -1 if failed parse, or (bytes_parsed<<4)|(month).  One-based month
   * is returned in the low nybble.
   */
  private static int parseMonth(byte[] buf, int i, int end) {
    int MM=0;
    byte[] MMM = null;
    OUTER: for( ; MM<MMS.length; MM++ ) {
      byte[][] mss = MMS[MM];
      INNER:
      for (byte[] ms : mss) {
        MMM = ms;
        if (MMM == null) continue;
        if (i + MMM.length > end) 
          continue INNER;
        for (int j = 0; j < MMM.length; j++)
          if (MMM[j] != Character.toLowerCase(buf[i + j]))
            continue INNER;
        if (i+MMM.length==end || buf[i + MMM.length] == '-' || isDigit(buf[i + MMM.length])) break OUTER;
      }
    }
    if( MM == MMS.length ) return -1; // No matching month
    MM++;                       // 1-based month
    return (MMM.length<<4)|MM;  // Return two values; skip in upper bytes, month in low nybble
  }

  /**
   * Attempts to parse time. Expects at least:
   * HH:mm:ss where : or . are accepted as delimiters
   * Additionally the time can contain either 1 or 3 or 9 places for fractions of a second
   * e.g. HH:mm:ss.SSS or HH:mm:ss.SSSnnnnnn
   * Note that only millisecond accuracy is stored
   * Additionally the time can end with AM|PM.
   * When AM or PM is present, HH must be 1-12. When absent, HH must be 0-23.
   * If the text doesn't fit this format it returns Long.MIN_VALUE to indicate failed parse
   *
   * @param buf - byte buffer containing text to parse
   * @param i - index of expected start of time string in buffer
   * @param end - index for end of time in buffer
   * @param yyyy - 4 digit year
   * @param MM - month of year (1-12)
   * @param dd - day of of month (1-31)
   * @return long representing time in currently timezone as milliseconds since UNIX epoch
   *         or Long.MIN_VALUE to represent failed time parse
   */
  private static long parseTime(byte[] buf, int i, int end, int yyyy, int MM, int dd, boolean timeOnly) {
    int HH =0, mm=0, ss=0, SSS=0, ndots=0;
    HH = digit(HH,buf[i++]);
    HH = buf[i]>='0' && buf[i]<= '9' ? digit(HH,buf[i++]) : HH;
    if(HH  < 0 || HH > 23 ) return Long.MIN_VALUE;
    if( buf[i] != ':' && buf[i] != '.' ) return Long.MIN_VALUE;
    if( buf[i]=='.' ) ndots++;
    ++i;
    mm = digit(mm,buf[i++]);
    mm = buf[i]>='0' && buf[i]<= '9' ? digit(mm,buf[i++]) : mm;
    if( mm < 0 || mm > 59 ) return Long.MIN_VALUE;
    if( i+2 >= buf.length ) return Long.MIN_VALUE;
    if( buf[i] != ':' && buf[i] != '.' ) return Long.MIN_VALUE;
    if( buf[i]=='.' ) ndots++;
    ++i;
    ss = digit(ss,buf[i++]);
    ss = buf[i]>='0' && buf[i]<= '9' ? digit(ss,buf[i++]) : ss;
    if( ss < 0 || ss > 59 ) return Long.MIN_VALUE;
    if( i<end && (buf[i] == ':' || buf[i] == '.' )) {
      if( buf[i]=='.' ) ndots++;
      i++;
      if( i<end ) SSS = digit(SSS,buf[i++]);
      if( i<end ) SSS = digit(SSS,buf[i++]);
      if( i<end ) SSS = digit(SSS,buf[i++]);
      if( SSS < 0 || SSS > 999 ) return Long.MIN_VALUE;
      while( i<end && isDigit(buf[i]) ) i++; // skip micros and nanos
    }
    if( i<end && buf[i] == '"' ) i++;
    if( i == end) {
      if( timeOnly && ndots==3 )
        return Long.MIN_VALUE; // Ambiguous: tell 1.2.3.4 apart from an IP address
      return new DateTime(yyyy, MM, dd, HH, mm, ss, getTimezone()).getMillis() + SSS;
    }

    // extract halfday of day, if present
    if( buf[i] == ' ' ) {
      ++i;
      if( i==end ) return new DateTime(yyyy, MM, dd, HH, mm, ss, getTimezone()).getMillis() + SSS;
    }
    if( (buf[i] == 'A' || buf[i] == 'P') && buf[i+1] == 'M') {
      if (HH < 1 || HH > 12) return Long.MIN_VALUE;
      // convert 1-12 hours into 0-23
      if (buf[i] == 'P') // PM
        if (HH < 12) HH += 12;
      else // AM
        if (HH == 12) HH = 0;
      i += 2;
    } else return Long.MIN_VALUE;

    if( i<end && buf[i] == '"' ) i++;
    if( i<end ) return Long.MIN_VALUE;
    return new DateTime(yyyy,MM,dd,HH,mm,ss,getTimezone()).getMillis()+SSS;
  }

  private static int digit( int x, int c ) {
    if( x < 0 || c < '0' || c > '9' ) return -1;
    return x*10+(c-'0');
  }
  private static boolean isDigit(byte b) { return (b >= '0' && b <= '9'); }
  private static boolean isChar(byte b) {
    if (b < 'A' || (b >'Z' && b < 'a')  || b > 'z') return false;
    else return true;
  }

  private static DateTimeZone _timezone;

  public static void setTimezone(String tz) {
    Set<String> idSet = DateTimeZone.getAvailableIDs();
    if(idSet.contains(tz))
      _timezone = DateTimeZone.forID(tz);
    else
      Log.err("Attempted to set unrecognized timezone: "+ tz);
  }

  public static DateTimeZone getTimezone() {
    return _timezone == null ? DateTimeZone.getDefault() : _timezone;
  }

  public static String listTimezones() {
    DateTimeFormatter offsetFormatter = new DateTimeFormatterBuilder().appendTimeZoneOffset(null, true, 2, 4).toFormatter();
    Set<String> idSet = DateTimeZone.getAvailableIDs();
    Map<String, String> tzMap = new TreeMap();
    Iterator<String> it = idSet.iterator();
    String id, cid, offset, key, output;
    DateTimeZone tz;
    int i = 0;
    long millis = System.currentTimeMillis();


    // collect canonical and alias IDs into a map
    while (it.hasNext()) {
      id = it.next();
      tz = DateTimeZone.forID(id);
      cid = tz.getID();
      offset = offsetFormatter.withZone(tz).print(tz.getStandardOffset(millis));
      key = offset + " " + cid;
      if (id == cid) { // Canonical ID
        if (!tzMap.containsKey(key)) tzMap.put(key, "");
      }  else {// alias ID
        if (!tzMap.containsKey(key))
          tzMap.put(key, "");
        tzMap.put(key,  tzMap.get(key) + ", " + id);
      }
    }

    // assemble result
    output = "StandardOffset CanonicalID, Aliases\n";
    for (Map.Entry<String, String> e : tzMap.entrySet())
      output += e.getKey() + e.getValue()+"\n";

    return output;
  }

  /**
   * Factory to create a formatter from a strptime pattern string.
   * This models the commonly supported features of strftime from POSIX
   * (where it can).
   * <p>
   * The format may contain locale specific output, and this will change as
   * you change the locale of the formatter.
   * Call DateTimeFormatter.withLocale(Locale) to switch the locale.
   * For example:
   * <pre>
   * DateTimeFormat.forPattern(pattern).withLocale(Locale.FRANCE).print(dt);
   * </pre>
   *
   * @param pattern  pattern specification
   * @return the formatter
   *  @throws IllegalArgumentException if the pattern is invalid
   */
  public static DateTimeFormatter forStrptimePattern(String pattern) {
    if (pattern == null || pattern.length() == 0)
      throw new IllegalArgumentException("Empty date time pattern specification");

    DateTimeFormatterBuilder builder = new DateTimeFormatterBuilder();
    parseToBuilder(builder, pattern);
    DateTimeFormatter formatter = builder.toFormatter();

    return formatter;
  }

  /**
   * Parses the given pattern and appends the rules to the given
   * DateTimeFormatterBuilder. See strptime man page for valid patterns.
   *
   * @param pattern  pattern specification
   * @throws IllegalArgumentException if the pattern is invalid
   */
  private static void parseToBuilder(DateTimeFormatterBuilder builder, String pattern) {
    int length = pattern.length();
    int[] indexRef = new int[1];

    for (int i=0; i<length; i++) {
      indexRef[0] = i;
      String token = parseToken(pattern, indexRef);
      i = indexRef[0];

      int tokenLen = token.length();
      if (tokenLen == 0) {
        break;
      }
      char c = token.charAt(0);

      if (c == '%' && token.charAt(1) != '%') {
        c = token.charAt(1);
        switch(c) {
          case 'a':
            builder.appendDayOfWeekShortText();
            break;
          case 'A':
            builder.appendDayOfWeekText();
            break;
          case 'b':
          case 'h':
            builder.appendMonthOfYearShortText();
            break;
          case 'B':
            builder.appendMonthOfYearText();
            break;
          case 'c':
            builder.appendDayOfWeekShortText();
            builder.appendLiteral(' ');
            builder.appendMonthOfYearShortText();
            builder.appendLiteral(' ');
            builder.appendDayOfMonth(2);
            builder.appendLiteral(' ');
            builder.appendHourOfDay(2);
            builder.appendLiteral(':');
            builder.appendMinuteOfHour(2);
            builder.appendLiteral(':');
            builder.appendSecondOfMinute(2);
            builder.appendLiteral(' ');
            builder.appendYear(4,4);
            break;
          case 'C':
            builder.appendCenturyOfEra(1,2);
            break;
          case 'd':
            builder.appendDayOfMonth(2);
            break;
          case 'D':
            builder.appendMonthOfYear(2);
            builder.appendLiteral('/');
            builder.appendDayOfMonth(2);
            builder.appendLiteral('/');
            builder.appendTwoDigitYear(2019);
            break;
          case 'e':
            builder.appendOptional(DateTimeFormat.forPattern("' '").getParser());
            builder.appendDayOfMonth(2);
            break;
          case 'F':
            builder.appendYear(4,4);
            builder.appendLiteral('-');
            builder.appendMonthOfYear(2);
            builder.appendLiteral('-');
            builder.appendDayOfMonth(2);
            break;
          case 'g':
          case 'G':
            break; //for output only, accepted and ignored for input
          case 'H':
            builder.appendHourOfDay(2);
            break;
          case 'I':
            builder.appendClockhourOfHalfday(2);
            break;
          case 'j':
            builder.appendDayOfYear(3);
            break;
          case 'k':
            builder.appendOptional(DateTimeFormat.forPattern("' '").getParser());
            builder.appendHourOfDay(2);
            break;
          case 'l':
            builder.appendOptional(DateTimeFormat.forPattern("' '").getParser());
            builder.appendClockhourOfHalfday(2);
            break;
          case 'm':
            builder.appendMonthOfYear(2);
            break;
          case 'M':
            builder.appendMinuteOfHour(2);
            break;
          case 'n':
            break;
          case 'p':
            builder.appendHalfdayOfDayText();
            break;
          case 'r':
            builder.appendClockhourOfHalfday(2);
            builder.appendLiteral(':');
            builder.appendMinuteOfHour(2);
            builder.appendLiteral(':');
            builder.appendSecondOfMinute(2);
            builder.appendLiteral(' ');
            builder.appendHalfdayOfDayText();
            break;
          case 'R':
            builder.appendHourOfDay(2);
            builder.appendLiteral(':');
            builder.appendMinuteOfHour(2);
            break;
          case 'S':
            builder.appendSecondOfMinute(2);
            break;
          case 't':
            break;
          case 'T':
            builder.appendHourOfDay(2);
            builder.appendLiteral(':');
            builder.appendMinuteOfHour(2);
            builder.appendLiteral(':');
            builder.appendSecondOfMinute(2);
            break;
/*          case 'U':  //FIXME Joda does not support US week start (Sun), this will be wrong
            builder.appendWeekOfYear(2);
            break;
          case 'u':
            builder.appendDayOfWeek(1);
            break;*/
          case 'V':
            break; //accepted and ignored
/*          case 'w':  //FIXME Joda does not support US week start (Sun), this will be wrong
            builder.appendDayOfWeek(1);
            break;
          case 'W':
            builder.appendWeekOfYear(2);
            break;*/
          case 'x':
            builder.appendTwoDigitYear(2019);
            builder.appendLiteral('/');
            builder.appendMonthOfYear(2);
            builder.appendLiteral('/');
            builder.appendDayOfMonth(2);
            break;
/*          case 'X':  //Results differ between OSX and Linux
            builder.appendHourOfDay(2);
            builder.appendLiteral(':');
            builder.appendMinuteOfHour(2);
            builder.appendLiteral(':');
            builder.appendSecondOfMinute(2);
            break;*/
          case 'y': //POSIX 2004 & 2008 says 69-99 -> 1900s, 00-68 -> 2000s
            builder.appendTwoDigitYear(2019);
            break;
          case 'Y':
            builder.appendYear(4,4);
            break;
          case 'z':
            builder.appendTimeZoneOffset(null, "z", false, 2, 2);
            break;
          case 'Z':
            break;  //for output only, accepted and ignored for input
          default:  // No match, ignore
            builder.appendLiteral('\'');
            builder.appendLiteral(token);
            Log.warn(token + "is not acceptted as a parse token, treating as a literal");
        }
      } else {
        if (c == '\'') {
          String sub = token.substring(1);
          if (sub.length() > 0) {
            // Create copy of sub since otherwise the temporary quoted
            // string would still be referenced internally.
            builder.appendLiteral(new String(sub));
          }
        } else throw new IllegalArgumentException("Unexpected token encountered parsing format string:" + c);
      }
    }
  }

  /**
   * Parses an individual token.
   *
   * @param pattern  the pattern string
   * @param indexRef  a single element array, where the input is the start
   *  location and the output is the location after parsing the token
   * @return the parsed token
   */
  private static String parseToken(String pattern, int[] indexRef) {
    StringBuilder buf = new StringBuilder();

    int i = indexRef[0];
    int length = pattern.length();

    char c = pattern.charAt(i);
    if (c == '%' && i + 1 < length && pattern.charAt(i+1) != '%') {
      //Grab pattern tokens
      c = pattern.charAt(++i);
      //0 is ignored for input, and this ignores alternative religious eras
      if ((c == '0' || c == 'E') && i + 1 >= length) c = pattern.charAt(++i);
      buf.append('%');
      buf.append(c);
    } else { // Grab all else as text
      buf.append('\'');  // mark literals with ' in first place
      buf.append(c);
      for (i++; i < length;i++) {
        c = pattern.charAt(i);
        if (c == '%' ) { // consume literal % otherwise break
          if (i + 1 < length && pattern.charAt(i + 1) == '%') i++;
          else { i--; break; }
        }
        buf.append(c);
      }
    }

    indexRef[0] = i;
    return buf.toString();
  }
}
