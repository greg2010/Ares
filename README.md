# org.red/ares

A tool that fetches information from [zKillboard] and posts relevant killmails to a discord channel via a webhook.

Example:

![alt text](https://i.imgur.com/VwvPv7p.png)


[zKillboard]: https://zkillboard.com

## Releases and Dependency Information

The project is intended to be used as an application, not a library, and therefore not published to clojars/maven.
The latest precompiled binaries can be found in releases tab on github.

## Usage

Supply a config file via a cmd parameter `-c`

Example: `java -jar ares-0.3.0-SNAPSHOT-standalone.jar -c example-config.edn`

Example config can be found [here](https://raw.githubusercontent.com/greg2010/Ares/master/example-config.edn).

## Change Log

* Version 0.3.1-SNAPSHOT - Fix bug that caused all killmails to be recognized as not friendly
* Version 0.3.0-SNAPSHOT - Support for multiple destinations, filtering based on region
* Version 0.1.0-SNAPSHOT - Initial release



## Copyright and License
MIT License


Copyright © 2018 greg2010

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.