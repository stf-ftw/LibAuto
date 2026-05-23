/*
*  This file is part of aasdk library project.
*  Copyright (C) 2018 f1x.studio (Michal Szwaj)
*
*  aasdk is free software: you can redistribute it and/or modify
*  it under the terms of the GNU General Public License as published by
*  the Free Software Foundation; either version 3 of the License, or
*  (at your option) any later version.

*  aasdk is distributed in the hope that it will be useful,
*  but WITHOUT ANY WARRANTY; without even the implied warranty of
*  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*  GNU General Public License for more details.
*
*  You should have received a copy of the GNU General Public License
*  along with aasdk. If not, see <http://www.gnu.org/licenses/>.
*/

#include <f1x/aasdk/Messenger/MessageInStream.hpp>
#include <f1x/aasdk/Error/Error.hpp>

namespace f1x
{
namespace aasdk
{
namespace messenger
{

MessageInStream::MessageInStream(boost::asio::io_service& ioService, transport::ITransport::Pointer transport, ICryptor::Pointer cryptor)
    : strand_(ioService)
    , transport_(std::move(transport))
    , cryptor_(std::move(cryptor))
    , recentFrameType_(FrameType::BULK)
    , recentChannelId_(ChannelId::NONE)
    , recentEncryptionType_(EncryptionType::PLAIN)
    , recentMessageType_(MessageType::SPECIFIC)
{

}

void MessageInStream::startReceive(ReceivePromise::Pointer promise)
{
    boost::asio::dispatch(strand_, [this, self = this->shared_from_this(), promise = std::move(promise)]() mutable {
        if(promise_ == nullptr)
        {
            promise_ = std::move(promise);
            this->receiveNextFrameHeader();
        }
        else
        {
            promise->reject(error::Error(error::ErrorCode::OPERATION_IN_PROGRESS));
        }
    });
}

void MessageInStream::receiveNextFrameHeader()
{
    auto transportPromise = transport::ITransport::ReceivePromise::defer(strand_);
    transportPromise->then(
        [this, self = this->shared_from_this()](common::Data data) mutable {
            this->receiveFrameHeaderHandler(common::DataConstBuffer(data));
        },
        [this, self = this->shared_from_this()](const error::Error& e) mutable {
            this->clearReceiveState();
            promise_->reject(e);
            promise_.reset();
        });

    transport_->receive(FrameHeader::getSizeOf(), std::move(transportPromise));
}

void MessageInStream::receiveFrameHeaderHandler(const common::DataConstBuffer& buffer)
{
    FrameHeader frameHeader(buffer);

    recentFrameType_ = frameHeader.getType();
    recentChannelId_ = frameHeader.getChannelId();
    recentEncryptionType_ = frameHeader.getEncryptionType();
    recentMessageType_ = frameHeader.getMessageType();
    const size_t frameSize = FrameSize::getSizeOf(frameHeader.getType() == FrameType::FIRST ? FrameSizeType::EXTENDED : FrameSizeType::SHORT);

    auto transportPromise = transport::ITransport::ReceivePromise::defer(strand_);
    transportPromise->then(
        [this, self = this->shared_from_this()](common::Data data) mutable {
            this->receiveFrameSizeHandler(common::DataConstBuffer(data));
        },
        [this, self = this->shared_from_this()](const error::Error& e) mutable {
            this->clearReceiveState();
            promise_->reject(e);
            promise_.reset();
        });

    transport_->receive(frameSize, std::move(transportPromise));
}

void MessageInStream::receiveFrameSizeHandler(const common::DataConstBuffer& buffer)
{
    auto transportPromise = transport::ITransport::ReceivePromise::defer(strand_);
    transportPromise->then(
        [this, self = this->shared_from_this()](common::Data data) mutable {
            this->receiveFramePayloadHandler(common::DataConstBuffer(data));
        },
        [this, self = this->shared_from_this()](const error::Error& e) mutable {
            this->clearReceiveState();
            promise_->reject(e);
            promise_.reset();
        });

    FrameSize frameSize(buffer);
    transport_->receive(frameSize.getSize(), std::move(transportPromise));
}

void MessageInStream::receiveFramePayloadHandler(const common::DataConstBuffer& buffer)
{
    auto message = this->getMessageForRecentFrame();

    if(message->getEncryptionType() == EncryptionType::ENCRYPTED)
    {
        try
        {
            cryptor_->decrypt(message->getPayload(), buffer);
        }
        catch(const error::Error& e)
        {
            this->clearReceiveState();
            promise_->reject(e);
            promise_.reset();
            return;
        }
    }
    else
    {
        message->insertPayload(buffer);
    }

    if(recentFrameType_ == FrameType::BULK || recentFrameType_ == FrameType::LAST)
    {
        if(recentFrameType_ == FrameType::LAST)
        {
            partialMessages_.erase(recentChannelId_);
        }

        promise_->resolve(std::move(message));
        promise_.reset();
    }
    else
    {
        this->receiveNextFrameHeader();
    }
}

Message::Pointer MessageInStream::createMessageFromRecentFrame()
{
    return std::make_shared<Message>(recentChannelId_, recentEncryptionType_, recentMessageType_);
}

Message::Pointer MessageInStream::getMessageForRecentFrame()
{
    if(recentFrameType_ == FrameType::BULK)
    {
        return this->createMessageFromRecentFrame();
    }

    if(recentFrameType_ == FrameType::FIRST)
    {
        auto message = this->createMessageFromRecentFrame();
        partialMessages_[recentChannelId_] = message;
        return message;
    }

    auto it = partialMessages_.find(recentChannelId_);
    if(it != partialMessages_.end())
    {
        return it->second;
    }

    auto message = this->createMessageFromRecentFrame();
    partialMessages_[recentChannelId_] = message;
    return message;
}

void MessageInStream::clearReceiveState()
{
    partialMessages_.clear();
}

}
}
}
