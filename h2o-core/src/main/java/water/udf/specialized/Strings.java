package water.udf.specialized;

import water.fvec.Chunk;
import water.fvec.Vec;
import water.parser.BufferedString;
import water.udf.ColumnFactory;
import water.udf.DataChunk;
import water.udf.DataColumn;
import water.udf.DataColumns;

/**
 * Factory for strings column
 */
public class Strings extends DataColumns.BaseFactory<String> {
  public static final Strings Strings = new Strings();
  
  public Strings() {
    super(Vec.T_STR, "Strings");
  }

  static class StringChunk extends DataChunk<String> {
    /**
     * deserialization :(
     */
    public StringChunk() {}
    
    public StringChunk(Chunk c) { super(c); }
    @Override
    public String get(long idx) {
      int i = index4(idx);
      try {
        return asString(c.atStr(new BufferedString(), i));
      } catch (IllegalArgumentException iae) {
        if (iae.getMessage().equals("Not a String")) return null;
        throw new IllegalArgumentException("idx was " + Long.toHexString(idx), iae);
      } catch (ArrayIndexOutOfBoundsException aie) {
        throw new IllegalArgumentException("idx was " + Long.toHexString(idx), aie);
      }
    }

    @Override
    public void set(long idx, String value) {
      int i = index4(idx);
      c.set(i, value);
    }
  }
  
  
  @Override
  public DataChunk<String> apply(final Chunk c) {
    return new StringChunk(c);
  }

  static class StringColumn extends DataColumn<String> {
    /**
     * deserialization :(
     */
    public StringColumn() {}
    
    StringColumn(Vec vec, ColumnFactory<String> factory) { super(vec, factory); }

    @Override
    public String get(long idx) {
      StringChunk c = new StringChunk(chunkAt(idx));
      return c.get(idx);
    }

    @Override
    public void set(long idx, String value) {
      StringChunk c = new StringChunk(chunkAt(idx));
      c.set(idx, value);
    }
  }
  
  @Override
  public DataColumn<String> newColumn(final Vec vec) {
    if (vec.get_type() != Vec.T_STR)
      throw new IllegalArgumentException("Expected type T_STR, got " + vec.get_type_str());
    return new StringColumn(vec, this);
  }
  
  private static String asString(Object x) { return x == null ? null : x.toString(); }

}
