package ch.epfl.biop.wrappers;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import ch.epfl.biop.wrappers.cellpose.Cellpose;
import ch.epfl.biop.wrappers.elastix.Elastix;
import ch.epfl.biop.wrappers.transformix.Transformix;

public class BiopWrappersCheck {
	
	public static String reportAllWrappers() {
		String output = "";
		for (Method m: BiopWrappersCheck.class.getMethods()) {
			if (m.isAnnotationPresent(WrapperCheck.class)) {
				try {
					output+= m.getAnnotation(WrapperCheck.class).title()+"\t->\t";
					boolean set = (boolean)(m.invoke(null, null));
					if (set) {
						output+="set :-) \n";
					} else {
						output+="not set :-( \n";
					}
				} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		return output;
	}
	
	@WrapperCheck(title="Elastix")
	public static boolean isElastixSet() {
		try {
			Elastix.execute("--help");
			return true;
		} catch (IOException|InterruptedException e) {
        	e.printStackTrace();
        	return false;
        } 	
	}
	
	@WrapperCheck(title="Transformix")
	public static boolean isTransformixSet() {
		try {
			Transformix.execute("--help");
			return true;
		} catch (IOException|InterruptedException e) {
			e.printStackTrace();
			return false;
		}
	}
	@WrapperCheck(title="Cellpose")
	public static boolean isCellposeSet() {
		try {
			Cellpose.execute("--help");
			return true;
		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
			return false;
		}
	}

	/*@WrapperCheck(title="Ilastik")
	public static boolean isIlastikSet() {
		try {
			Ilastik.execute("--help");
			return true;
		} catch (IOException|InterruptedException e) {
			e.printStackTrace();
			return false;
		}
	}*/
}
