#pragma once

#include <boost/asio/io_context.hpp>
#include <boost/asio/strand.hpp>

namespace boost
{
namespace asio
{

#if !defined(BOOST_ASIO_HAS_IO_SERVICE)
#define BOOST_ASIO_HAS_IO_SERVICE 1

class io_service : public io_context
{
public:
    using io_context::io_context;

    class strand : public boost::asio::strand<io_context::executor_type>
    {
    public:
        explicit strand(io_service& service)
            : boost::asio::strand<io_context::executor_type>(service.get_executor())
        {
        }

        io_service& get_io_service()
        {
            return static_cast<io_service&>(this->context());
        }
    };
};

#endif

}
}
