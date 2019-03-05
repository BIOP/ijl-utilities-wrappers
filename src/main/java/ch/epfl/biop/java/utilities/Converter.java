package ch.epfl.biop.java.utilities;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)

/**
 * Annotation to notify that the method is a converter (from one class to another)
 */

public @interface Converter {

}
