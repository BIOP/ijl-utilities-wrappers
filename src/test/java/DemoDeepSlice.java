
import ch.epfl.biop.wrappers.deepslice.ij2commands.DeepSliceFolderCommand;
import net.imagej.ImageJ;

public class DemoDeepSlice {

    public static void main(String... args) throws Exception {

        ImageJ ij = new ImageJ();
        ij.ui().showUI();

        ij.command().run(DeepSliceFolderCommand.class, true,
                "input_folder", "src/test/resources/deepslice_test_folder",
                "output_folder", null,
                "model", "mouse",
                "ensemble", false,
                "section_number", true,
                "propagate_angle", true,
                "enforce_index_order", true,
                "use_enforce_index_spacing", false,
                "enforce_index_spacing", "None"
                ).get();

        ij.command().run(DeepSliceFolderCommand.class, true,
                "input_folder", "src/test/resources/deepslice_test_folder_single",
                "output_folder", null,
                "model", "mouse",
                "ensemble", false,
                "section_number", true,
                "propagate_angle", false,
                "enforce_index_order", false,
                "use_enforce_index_spacing", false,
                "enforce_index_spacing", "None"
        ).get();

        ij.command().run(DeepSliceFolderCommand.class, true,
                "input_folder", "src/test/resources/deepslice_test_folder_double",
                "output_folder", null,
                "model", "mouse",
                "ensemble", false,
                "section_number", true,
                "propagate_angle", false,
                "enforce_index_order", false,
                "use_enforce_index_spacing", false,
                "enforce_index_spacing", "None"
        ).get();

    }
}
