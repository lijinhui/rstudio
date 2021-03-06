/*
 * RGraphicsTypes.hpp
 *
 * Copyright (C) 2009-11 by RStudio, Inc.
 *
 * This program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */

#ifndef R_SESSION_GRAPHICS_TYPES_HPP
#define R_SESSION_GRAPHICS_TYPES_HPP

#include <boost/utility.hpp>
#include <boost/format.hpp>
#include <boost/function.hpp>

typedef struct SEXPREC *SEXP;

namespace core {
   class Error;
   class FilePath;
}

namespace r {
namespace session {
namespace graphics {

struct DisplaySize
{
   DisplaySize(int width, int height) : width(width), height(height) {}
   DisplaySize() : width(0), height(0) {}
   int width;
   int height;
   
   bool operator==(const DisplaySize& other) const
   {
      return width == other.width &&
             height == other.height;
   }
   
   bool operator!=(const DisplaySize& other) const
   {
      return !(*this == other);
   }
};

struct GraphicsDeviceFunctions
{
   boost::function<DisplaySize()> displaySize;
   boost::function<core::Error(const core::FilePath&)> saveSnapshot;
   boost::function<core::Error(const core::FilePath&)> restoreSnapshot;
   boost::function<core::Error(const core::FilePath&)> saveAsImageFile;
   boost::function<void()> copyToActiveDevice;
   boost::function<std::string()> imageFileExtension;
   boost::function<void()> close;
   boost::function<void()> onBeforeExecute;
};  


} // namespace graphics
} // namespace session
} // namespace r


#endif // R_SESSION_GRAPHICS_TYPES_HPP 

