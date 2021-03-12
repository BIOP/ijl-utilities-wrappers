[![](https://travis-ci.com/BIOP/ijl-utilities-wrappers.svg?branch=master)](https://travis-ci.com/BIOP/ijl-utilities-wrappers)

# Wrappers for FIJI

* Cellpose
* Elastix
* Ilastik
* Transformix
* Java Converter Utilities
** Images
** Rois

<h1>Cellpose</h1> 

The ***Cellpose*** wrapper is an ImageJ2 command that enables using a working Cellpose virtual environement (either conda, or venv) from Fiji.

Briefly, ***Cellpose*** wrapper sequentially:
- saves the current Fiji image in a temporary folder
- starts the cellpose-env and runs Cellpose with defined parameters
- opens the created label image in Fiji
- cleans the temporary folder

**NOTE** : to use a conda environment you need to enable conda command outside conda prompt, *cf* installation instructions below.


<h2>Installation</h2>
You'll find here some instructions to install the ***Cellpose*** commands and some guidance to set up a Cellpose virtual environment.

<h3>Fiji - Cellpose wrapper</h2>
- Please use our update site ***(PTBIOP | https://biop.epfl.ch/Fiji-Update/)*** , [find more details here](https://c4science.ch/w/bioimaging_and_optics_platform_biop/image-processing/imagej_tools/update-site/).
- Restart Fiji
- Plugins>BIOP>Cellpose> Define Env. & prefs. , select the path to your working Cellpose Virtual Environment.

Congratulation you can now Cellpose-ing your first image from Fiji!

<h3>Cellpose Virtual Environment </h2>

You can find [instructions to install cellpose environment on cellpose repo](https://github.com/MouseLand/cellpose)

Please find below some additionnal information, provided "as is" without any warranties of sucessful installation, nor further support.

<h4>More on venv installation</h3>

Please [find here a very detailed installation procedure with venv](https://c4science.ch/w/bioimaging_and_optics_platform_biop/computers-servers/software/gpu-deep-learning/virtualenv/).

<h4>More on conda installation</h3>

<h5> Windows </h5>

<h6> Cellpose-GPU </h6>

A succesful GPU instalation was possible with Win10 & NVIDIA GeForce RTX 2080 Ti, following [the detailed installation procedure described for venv](https://c4science.ch/w/bioimaging_and_optics_platform_biop/computers-servers/software/gpu-deep-learning/virtualenv/), with some minor changes :
- [CUDA Toolkit installer 10.1](https://developer.nvidia.com/cuda-10.1-download-archive-base?target_os=Windows&target_arch=x86_64&target_version=10&target_type=exenetwork), 
do not limit the installation to "the Developer and Runtime parts", the simplest (not the cleanest) is to run the default installation.(§)
- import conda env using [cellpose_biop_gpu.yml file](https://github.com/BIOP/ijl-utilities-wrappers/raw/conda-cellpose-wrapper/resources/cellpose_biop_gpu.yml)(§§)

**NOTE (§) ** : nvcc is required for the installation procedure and "the cudatoolkit packages available via Conda do not include [it]" ( [more about it here](https://horovod.readthedocs.io/en/stable/conda_include.html)). 
To check nvcc status, you can (in a command prompt) type  ``nvcc- V``, you should get something close to :

`` nvcc: NVIDIA (R) Cuda compiler driver`` 

`` Copyright (c) 2005-2019 NVIDIA Corporation`` 

`` Built on Sun_Jul_28_19:12:52_Pacific_Daylight_Time_2019`` 

`` Cuda compilation tools, release 10.1, V10.1.243`` 

**NOTE (§§)** : a yml file subtility I learnt on this journey, you can enforce a certain package_name::channel_name


<h6> Enable conda command outside conda prompt </h6>
You need to follow this two steps procedure to enable Windows to use conda from cmd.exe.

- 1-Into the environment variable , edit PATH , add path to your ``..\Anaconda3\condabin ``default would be ``C:\ProgramData\Anaconda3\condabin`` 
- 2-Open a new Powershell, run the following command once to initialize conda:
  conda init
  
From now on you don't need to run a conda prompt you can simply activate a conda env from cmd.exe.
To check if it works, you can:
- 1.Press windows key, type ``cmd.exe`` (to get a command promt)
- 2.Type ``conda env list``
You should get the list of your conda envs.




<h5> Mac </h5>
**Please contact us with succesfull procedure **

<h5> Linux </h5>
** Please contact us with succesfull procedure **



<h1>Elastix</h1>

<h1>Ilastik</h1>


<h1>Transformix</h1>


 