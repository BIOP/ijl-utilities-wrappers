[![](https://github.com/BIOP/ijl-utilities-wrappers/actions/workflows/build-main.yml/badge.svg)](https://github.com/BIOP/ijl-utilities-wrappers/actions/workflows/build-main.yml)

# Wrappers for FIJI

* Cellpose
* Elastix
* Ilastik
* Transformix
* Java Converter Utilities
** Images
** Rois

<h1>Cellpose</h1> 

**NOTE** : up to cellpose 0.6 (cellpose/omnipose 0.7 is under dev)

The **Cellpose** wrapper is an ImageJ2 command that enables using a working Cellpose virtual environment (either conda, or venv) from Fiji.

Briefly, **Cellpose** wrapper sequentially:
- saves the current Fiji image in a temporary folder
- starts the cellpose-env and runs Cellpose with defined parameters
- opens the created label image in Fiji
- cleans the temporary folder



<h2> I. Installation</h2>
You'll find here some instructions to install the **_Cellpose_** wrapper and some guidance to set up a Cellpose virtual environment.

**NOTE** : if you rely on conda, the Cellpose wrapper requires to enable the conda command outside of conda prompt, [_cf_ installation instructions below : ](https://github.com/BIOP/ijl-utilities-wrappers/tree/master#-enable-conda-command-outside-conda-prompt-).

<h3> I.A. Cellpose Virtual Environment </h2>

You can find [instructions to install Cellpose environment on Cellpose repo](https://github.com/MouseLand/cellpose)

Please find below some  information, provided "as is" without any warranties of successful installation, nor further support.

<h4> I.A.1. More on venv installation</h3>

Please [find here a very detailed installation procedure with venv](https://c4science.ch/w/bioimaging_and_optics_platform_biop/computers-servers/software/gpu-deep-learning/virtualenv/).

<h4> I.A.2. More on conda installation</h3>

<h5> I.A.2.a. Windows </h5>
**NOTE** : if you rely on conda, the Cellpose wrapper requires to enable the conda command outside of conda prompt, [_cf_ installation instructions below : ](https://github.com/BIOP/ijl-utilities-wrappers/tree/master#-enable-conda-command-outside-conda-prompt-).

<h6> Enable conda command outside conda prompt </h6>
You need to follow this two steps procedure to enable Windows to use conda from cmd.exe.

- 1-Into the environment variable , edit PATH , add path to your ``..\Anaconda3\condabin ``default would be ``C:\ProgramData\Anaconda3\condabin``
- 2-Open a new PowerShell (and/or PowerShell (x86) ), run the following command once to initialize conda:
  `` conda init``

From now on you don't need to run a conda prompt you can simply activate a conda env from `` cmd.exe`` .

To check if it works, you can:
- 1.Press windows key, type ``cmd.exe`` (to get a command promt)
- 2.Type ``conda env list``
  You should get the list of your conda envs.

<h6> Conda cellpose-GPU </h6>

A successful GPU installation was possible with Win10 & NVIDIA GeForce RTX 2080 Ti, following [the detailed installation procedure described for venv](https://c4science.ch/w/bioimaging_and_optics_platform_biop/computers-servers/software/gpu-deep-learning/virtualenv/) following installation of drivers, VisualStudio, CUDA Toolkit, CuDDN before using Anaconda and the yml file below:
| CUDA Toolkit | cuDNN | cellpose | yml |
| ------------- | ------------- | ------------- | ------------- |
| [CUDA Toolkit installer 10.1](https://developer.nvidia.com/cuda-10.1-download-archive-base?target_os=Windows&target_arch=x86_64&target_version=10&target_type=exenetwork) (§)| 7.6.0 | 0.6| [cellpose_biop_gpu.yml file](https://github.com/BIOP/ijl-utilities-wrappers/raw/master/resources/cellpose_biop_gpu.yml) (§§)| 
| [CUDA Toolkit installer 11.3](https://developer.nvidia.com/cuda-11-3-1-download-archive) | [8.2.1](https://developer.nvidia.com/rdp/cudnn-archive) | 0.6 | [cellpose06_biop_gpu_113-821.yml file](https://github.com/BIOP/ijl-utilities-wrappers/raw/master/resources/cellpose06_biop_gpu_113-821.yml) |
| [CUDA Toolkit installer 11.3](https://developer.nvidia.com/cuda-11-3-1-download-archive) | [8.2.1](https://developer.nvidia.com/rdp/cudnn-archive) | 0.7| [cellpose07_biop_gpu_113-821.yml file](https://github.com/BIOP/ijl-utilities-wrappers/raw/master/resources/cellpose07_biop_gpu_113-821.yml) |

**NOTE** if you experience "tensors error" 
Current fix (from [cellpose issue](https://github.com/MouseLand/cellpose/issues/378#issuecomment-976767543)) is : 
- locate `dynamics.py`
- in `line 104` replace :  
  - `meds = torch.from_numpy(centers.astype(int)).to(device)` 
  - by
  - `meds = torch.from_numpy(centers.astype(int)).to(torch.long).to(device)`

**(§)**: nvcc is required for the installation procedure and "the cudatoolkit packages available via Conda do not include [it]" ( [more about this issue here](https://horovod.readthedocs.io/en/stable/conda_include.html)). 
To check nvcc status, you can (in a command prompt) type  ``nvcc- V``, you should get something close to :

`` nvcc: NVIDIA (R) Cuda compiler driver`` 

`` Copyright (c) 2005-2019 NVIDIA Corporation`` 

`` Built on Sun_Jul_28_19:12:52_Pacific_Daylight_Time_2019`` 

`` Cuda compilation tools, release 10.1, V10.1.243`` 

**(§§)** : a yml file subtility I learnt on this journey, you can enforce a certain channel_name::package_name

<h5> I.A.2.b. Mac </h5>

**_Please contact us with successful procedure._**

<h5> I.A.2.c. Linux </h5>

**_Please contact us with successful procedure_**


<h3> I.B. Fiji - Cellpose wrapper </h3>

**NOTE** The Fiji - Cellpose wrapper is useless without a working Cellpose environment, please see installation abobe (I.A.). 
To test if you have a working Cellpose environment:
1 - Activate your environment
2 - Type `python -m cellpose --help`
You should not get an error.


- Please use our update site **_(PTBIOP | https://biop.epfl.ch/Fiji-Update/)_** , [find more details here](https://c4science.ch/w/bioimaging_and_optics_platform_biop/image-processing/imagej_tools/update-site/).
- Restart Fiji
- ``Plugins>BIOP>Cellpose> Define Env. & prefs.`` 
  - Select the path to your working Cellpose virtual environment 
  - Select EnvType : ``conda`` or ``venv``
  - Select version : ``0.6`` or ``0.7``

<img src="https://github.com/BIOP/ijl-utilities-wrappers/blob/cellpose07/resources/cellposeSetup.png" title="CellposeSetup" width="50%" align="center">

Congratulation you can now use Cellpose on your first image from Fiji! :)

<h2> II. Using Fiji - Cellpose wrapper</h2>

The more "flexible" command is `Cellpose Advanced (own model)` which offers many parameters. 

<img src="https://github.com/BIOP/ijl-utilities-wrappers/blob/cellpose07/resources/cellposeAdvParam.png" title="CellposeCommandAdvanced" width="50%" align="center">

BUT in case you need more parameters, this command also comes with a string field for additional parameters following pattern : `--channel_axis,CHANNEL_AXIS,--dir_above`

For convenience 3 more commands exist:
- `Segment Nuclei`, no parameter, ideal to test on blobs
- `Segment Nuclei Advanced`, some parameter available
- `Cellpose Advanced` (same parameters as command `Cellpose Advanced (own model)` without possibility to select your own model)

**NOTE** We recommand users to prepare in Fiji the minimal image to be processed by cellpose before using the plugin.
For example, from a 4 channels image (with nuclei, membrane , proteinX, ... stainings) extract the membrane and nuclei channel, make a composite and run cellpose command on it.

For more info about parameters please refer to [cellpose.readthedocs.io](https://cellpose.readthedocs.io/en/latest/settings.html#)


<h1>Elastix</h1>

<h1>Ilastik</h1>


<h1>Transformix</h1>


 
