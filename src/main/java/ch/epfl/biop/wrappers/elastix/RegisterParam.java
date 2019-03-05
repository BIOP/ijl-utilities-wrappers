package ch.epfl.biop.wrappers.elastix;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to recognize Elastix registration parameters
 */

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface RegisterParam {

}
