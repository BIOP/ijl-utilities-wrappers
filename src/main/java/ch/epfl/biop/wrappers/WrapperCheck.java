package ch.epfl.biop.wrappers;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface WrapperCheck {
	String title();

}
