package core

import (
	"io"
	"net"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

type bufPoolImpl struct {
	p sync.Pool
}

func newBufPool(size int) *bufPoolImpl {
	return &bufPoolImpl{
		p: sync.Pool{
			New: func() interface{} {
				buf := make([]byte, size)
				return &buf
			},
		},
	}
}

func (bp *bufPoolImpl) Get() []byte {
	return *(bp.p.Get().(*[]byte))
}

func (bp *bufPoolImpl) Put(buf []byte) {
	bp.p.Put(&buf)
}

func pipeDirect(local net.Conn, ws *websocket.Conn) {
	var once sync.Once
	closeBoth := func() {
		local.Close()
		ws.Close()
	}
	defer once.Do(closeBoth)

	var wg sync.WaitGroup
	wg.Add(2)

	downlinkDone := make(chan struct{})

	go func() {
		defer wg.Done()
		defer close(downlinkDone)

		buf := bufPool.Get()
		defer bufPool.Put(buf)

		for {
			mt, r, err := ws.NextReader()
			if err != nil {
				break
			}
			if mt == websocket.BinaryMessage {
				if _, err := io.CopyBuffer(local, r, buf); err != nil {
					break
				}
			}
		}

		local.Close()
	}()

	go func() {
		defer wg.Done()

		buf := bufPool.Get()
		defer bufPool.Put(buf)

		for {
			n, err := local.Read(buf)
			if n > 0 {
				if werr := ws.WriteMessage(websocket.BinaryMessage, buf[:n]); werr != nil {
					break
				}
			}
			if err != nil {
				break
			}
		}

		select {
		case <-downlinkDone:
		case <-time.After(30 * time.Second):
		}

		once.Do(closeBoth)
	}()

	wg.Wait()
}