package ch.epfl.biop.wrappers.transformix.ij2commands;

import ch.epfl.biop.wrappers.elastix.RegisterHelper;
import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import ch.epfl.biop.wrappers.transformix.TransformHelper;
import ij.ImagePlus;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Transformix>Transform Image")

public class Transformix_TransformImgPlus implements Command  {
	@Parameter
    RegisterHelper rh;
	
	@Parameter
	ImagePlus img_in;
	
	@Parameter(type=ItemIO.OUTPUT)
	ImagePlus img_out;
	
	@Override
	public void run() {
		TransformHelper th = new TransformHelper();
		th.setTransformFile(rh);
		th.setImage(img_in);
		th.transform();
		img_out = (ImagePlus) (th.getTransformedImage().to(ImagePlus.class));
	}

	/*Fi
	le file = new File("/path/to/directory");
	String[] directories = file.list(new FilenameFilter() {
		@Override
		public boolean accept(File current, String name) {
			return new File(current, name).isDirectory();
		}
	});
	System.out.println(Arrays.toString(directories));*/
	
}
