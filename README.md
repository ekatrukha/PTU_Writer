# PTU_Writer
[FIJI](http://fiji.sc/) plugin generating PicoQuant ptu FLIM TTTR image file given a stack with lifetimes.  
Reverse-engineering of [PTU_Reader](https://github.com/UU-cellbiology/PTU_Reader) plugin.  

## How to install plugin

1. You need to download and install [FIJI](http://fiji.sc/#download) on your computer first.
2. [Download *PTU_Writer_...jar*](https://github.com/ekatrukha/PTU_Writer/releases) and place it in the "plugins" folder of FIJI.
3. Plugin will appear as *PTU_Writer* in FIJI's *Plugins* menu.

## How to run plugin

1. Click *PTU_Writer* line in ImageJ's *Plugins* menu.
2. In the following dialog select a TIF Z-stack, where Z corresponds to the "lifetime counts".
3. Plugin will read file's header and provide you with following options:  
![Menu](http://katpyxa.info/software/PTU_Reader/Menu2.png "Menu")
4. Choose what images/stacks you want to load (see detailed description below) and click *OK*.

## Updates history
v.0.0.1 (2024.11) First release.

---
Developed in [Cell Biology group](http://cellbiology.science.uu.nl/) of Utrecht University.  
Email katpyxa @ gmail.com for any questions/comments/suggestions.
