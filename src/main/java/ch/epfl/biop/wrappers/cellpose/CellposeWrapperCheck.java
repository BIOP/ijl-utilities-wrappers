package ch.epfl.biop.wrappers.cellpose;

import ch.epfl.biop.wrappers.WrapperCheck;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class CellposeWrapperCheck {
	
	public static String reportAllWrappers() {
		String output = "";
		for (Method m: CellposeWrapperCheck.class.getMethods()) {
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
}
