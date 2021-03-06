#
# SessionTeX.R
#
# Copyright (C) 2009-11 by RStudio, Inc.
#
# This program is licensed to you under the terms of version 3 of the
# GNU Affero General Public License. This program is distributed WITHOUT
# ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
# MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
# AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
#
#

.rs.addFunction("compilePdf", function(file, completedAction = "")
{
   # get the path info
   pathInfo = .Call("rs_pathInfo", file)
   
   # check for spaces in path (sweave chokes on these)
   if ( length(grep(" ", pathInfo$name)) > 0 )
   {
     stop(paste("Invalid filename: '", pathInfo$name,
                "' (TeX does not understand paths with spaces)",
                sep=""))
   }
     
   # determine the directory name of the passed file and setwd to it
   # (but restore to the current wd on exit)
   currentDir <- getwd()
   setwd(pathInfo$directory)
   
   # on exit restore working dir 
   on.exit(setwd(currentDir))
   
   # set the filename for the compile (will be changed if we Sweave)
   fileName <- pathInfo$name

   # check extension to see if we need to Sweave
   ext <- tolower(pathInfo$extension)
   if (ext == ".rnw" || ext == ".snw" || ext == ".nw")
   {
     .Call("rs_callSweave", R.home("bin"), fileName)
     fileName = paste(pathInfo$stem, ".tex", sep="")

     # validate the TeX file before proceeding (will be invalid if
     # Sweave aborted due to an R syntax or processing error)
     if ( !.Call("rs_validateTexFile", fileName) )
        return ()
   }
    
   # run texi2dvi
   cat("\n")
   cat("Running texi2dvi...")
   tools:::texi2dvi(file=fileName, pdf=TRUE, clean=TRUE)
   cat("completed\n\n")
 
   # check for completed action
   if (completedAction == "view")
     .Call("rs_viewPdf", pathInfo$path)
   else if (completedAction == "publish")
     .Call("rs_publishPdf", pathInfo$path)
})

.rs.addGlobalFunction("compilePdf", function(file)
{
   invisible(.rs.compilePdf(file, "view"))
})


.rs.addGlobalFunction("publishPdf", function(file)
{
   invisible(.rs.compilePdf(file,  "publish"))
})

.rs.addFunction("is_tex_installed", function()
{
    return(.rs.scalar(file.exists(Sys.which('pdflatex'))))
})

.rs.addJsonRpcHandler("is_tex_installed", .rs.is_tex_installed)
