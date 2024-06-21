package ch.epfl.biop.java.utilities;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Abstraction layer to handle objects which can be defined by non java compatible objects
 * For instance a set of ROI can be defined by a Roi ArrayList or RoiManager or a LabelImage
 * An Image can be a File or an ImagePlus
 * <p>
 * By Extending this class and providing converter method using the Converter annotation,
 * any 'ConvertibleObject' can be cast to different classes, provided a path of Converters
 * can be found from an existing object class to the required class
 * <p>
 * This converter path search is done recursively  (recursivity limit = 3 by default)
 * <p>
 * Example use:
 *
 * <pre>
 * ConvertibleRois cr = new ConvertibleRois();
 * // Populate rois with a remote svg image
 * {@code cr.set(new URL("https://upload.wikimedia.org/wikipedia/commons/f/f7/Bananas.svg"));}
 * cr.to(RoiManager.class); // converts to ROI Manager
 * import ch.epfl.biop.java.utilities.roi.ConvertibleRois;
 * import ij.plugin.frame.RoiManager;
 * import ij.ImagePlus;
 * ConvertibleRois cr = new ConvertibleRois();
 * // Populate rois with a remote svg image
 * {@code cr.set(new URL("https://api.brain-map.org/api/v2/svg/100960033?groups=28"));}
 * cr.to(RoiManager.class); // converts to ROI Manager
 * </pre>
 *
 */


public class ConvertibleObject {

	final Map<Class<?>, ArrayList<Method>> convertFwd = new HashMap<>();
	final Map<Class<?>, ArrayList<Method>> convertBwd = new HashMap<>();;
	
	public final Map<Class<?>,Object> states = new HashMap<>();
	
	public ConvertibleObject() {
        for (Method m:this.getClass().getMethods()) {
            if (m.isAnnotationPresent(Converter.class)) {
                registerConverter(m);
            }
        }
        states.put(this.getClass(), this);
	}
	
	private void registerConverter(Method m) {
		Class<?> c_in = m.getParameterTypes()[0].getComponentType();
		Class<?> c_out = m.getReturnType();
		if (convertFwd.containsKey(c_in)) {
			convertFwd.get(c_in).add(m);
		} else {
			ArrayList<Method> methodArray = new ArrayList<>();
			methodArray.add(m);
			convertFwd.put(c_in, methodArray);
		}
		if (convertBwd.containsKey(c_out)) {
			convertBwd.get(c_out).add(m);
		} else {
			ArrayList<Method> methodArray = new ArrayList<>();
			methodArray.add(m);
			convertBwd.put(c_out, methodArray);
		}
	}

	public Object to(Class<?> c_out, int recursivityLevel) {
		if (recursivityLevel==0) {
			return null;
		}
		if (states.containsKey(c_out)) {
			//System.out.println("Found -> "+c_out.getName()+" State");
			return states.get(c_out);
		} else {
			if (!states.isEmpty()) {
				// Try direct conversion
				if (convertBwd.get(c_out)!=null) {
					for (Method m: convertBwd.get(c_out)) {
						Class<?> c_in = m.getParameterTypes()[0];
						if (states.containsKey(c_in)) {
							try {
								//System.out.println("NR Converting:"+c_in.getName()+"->"+c_out.getName());
								Object obj_out = m.invoke(this,states.get(c_in));
								states.put(c_out, obj_out);
								return obj_out;
							} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
								e.printStackTrace();
							}
						}
					}
					// If not : recursivity
					for (Method m: convertBwd.get(c_out)) {
						Class<?> c_in = m.getParameterTypes()[0];
						Object obj_in = this.to(c_in, recursivityLevel-1);
						if (obj_in!=null) {//states.containsKey(c_in)) {
							try {
								//System.out.println("R Converting:"+c_in.getName()+"->"+c_out.getName());
								Object obj_out = m.invoke(this,states.get(c_in));
								states.put(c_out, obj_out);
								return obj_out;
							} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
								e.printStackTrace();
							}
						}
					}
				} else {
					return null;
				}
			}
		}
		return null;
	}
	
	public int MaxRecursivity = 3;
	
	public Object to(Class<?> c_out) {
		return to(c_out,MaxRecursivity);
	}
	
	public void clear() {
		states.clear();
	}
	
	public void clear(Class<?> c) {
		states.remove(c);
	}
	
	public void set(Object o) {
		states.put(o.getClass(), o);
	}
	
	public void set(Object o, Class<?> c) {
		states.put(c.getClass().cast(o),o);
	}

	// Naming

	String name=null;

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name=name;
	}

	public String toString() {
		if (name==null) {
			return super.toString();
		} else {
			return name;
		}
	}

}
