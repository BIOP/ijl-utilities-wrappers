#!/usr/bin/env python

import argparse

from DeepSlice.read_and_write import QuickNII_functions


def parse_arguments():
    parser = argparse.ArgumentParser(description="This is a command line interface to DeepSlice (https://github.com/PolarBean/DeepSlice)")
    parser.add_argument("model", help="DeepSlice model: mouse or rat")
    parser.add_argument("input_folder", help="path of the input folder")
    parser.add_argument("--output_folder", help="path of the output folder (input used by default)")
    parser.add_argument("--ensemble", action="store_true",
                        help="try with and without ensemble to find the model which best works for you")
    parser.add_argument("--section_numbers", action="store_true",
                        help="if you have section numbers included in the filename as _sXXX specify this")
    parser.add_argument("--propagate_angles", action="store_true",
                        help="if you would like to normalise the angles (you should)")
    parser.add_argument("--enforce_index_order", action="store_true",
                        help="to reorder your sections according to the section numbers")
    parser.add_argument("--enforce_index_spacing",
                        help="alternative to enforce_index_order: if you know the precise spacing (ie; 1, 2, 4, "
                             "indicates that section 3 has been left out of the series), then you can set the section "
                             "thickness in microns with this parameter")

    # enforce_index_spacing
    return parser.parse_args()


# Press the green button in the gutter to run the script.
if __name__ == '__main__':
    args = parse_arguments()
    from DeepSlice import DSModel

    model = DSModel(args.model)
    model.predict(args.input_folder, args.ensemble, args.section_numbers)
    # If you would like to normalise the angles (you should)
    if args.propagate_angles:
        model.propagate_angles()
    # To reorder your sections according to the section numbers
    if args.enforce_index_order:
        model.enforce_index_order()
    # alternatively if you know the precise spacing (ie; 1, 2, 4, indicates that section 3 has been left out of the series) Then you can use
    # Furthermore if you know the exact section thickness in microns this can be included instead of None
    if args.enforce_index_spacing is not None:
        model.enforce_index_spacing(section_thickness=args.enforce_index_spacing)
    # now we save which will produce a json file which can be placed in the same directory as your images and then opened with QuickNII.

    # saves json only
    if args.output_folder is not None:
        filename = args.output_folder
    else:
        filename = args.input_folder + 'results'

    target = model.config["target_volumes"][model.species]["name"]
    aligner = model.config["DeepSlice_version"]["prerelease"]
    QuickNII_functions.write_QUINT_JSON(
        df=model.predictions, filename=filename, aligner=aligner, target=target
    )