package com.yugabyte.ysql;

import java.util.logging.Level;

public class PropertiesUtil {

  public static final String RESTRICT_NEST_LOOP_KEY = "restrict-nest-loop";
  public static final String FIRST_PROPERTY_SEP = "\\?";
  private static final String PROPERTY_SEP = "&";
  private static final String EQUALS = "=";

  public static String getYBProperty(String url, String propName) {
    String[] urlParts = url.split(FIRST_PROPERTY_SEP);
    if (urlParts.length != 2) return null; // '?' should only come at most once!

    urlParts = urlParts[1].split(PROPERTY_SEP);
    StringBuilder sb = new StringBuilder();

    for (String part : urlParts) {
      if (part.startsWith(propName + EQUALS)) {
        String[] parts = part.split(EQUALS);
        if (parts.length != 2) {
          continue;
        }
        return parts[1];
      }
    }
    return null;
  }

}
