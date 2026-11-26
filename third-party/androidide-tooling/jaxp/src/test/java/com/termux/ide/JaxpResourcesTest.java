package com.termux.ide;

import jaxp.sun.org.apache.xerces.internal.impl.xpath.regex.ParseException;
import jaxp.sun.org.apache.xerces.internal.impl.xpath.regex.RegularExpression;
import org.junit.Test;
import static org.junit.Assert.*;

public class JaxpResourcesTest {
    @Test public void parserReportsInvalidPatternWithoutMissingResourceFailure() {
        try { new RegularExpression("[", "X"); fail(); }
        catch (ParseException expected) { assertNotNull(expected.getMessage()); assertFalse(expected.getMessage().isEmpty()); }
    }
}
