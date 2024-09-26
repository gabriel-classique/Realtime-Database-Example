package xcvi.dev.procreator.data

import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import xcvi.dev.procreator.domain.DataEntity
import xcvi.dev.procreator.domain.Error
import xcvi.dev.procreator.domain.Response
import xcvi.dev.procreator.domain.UserModel


object FirebaseService {

    private const val DATABASE_URL =
        "https://procreator-cloud-service-default-rtdb.europe-west1.firebasedatabase.app"
    private const val USER_PATH = "User"
    private const val DATA_PATH = "Data"

    private val db = FirebaseDatabase.getInstance(DATABASE_URL)
    private val auth = Firebase.auth


    init {
        db.setPersistenceEnabled(true)
        /*

        //sync necessary paths

        auth.currentUser?.let { user ->
            db.reference.child(DATA_PATH).child(user.uid).keepSynced(true)
        }
         */
    }


    fun observeData(): Flow<List<DataEntity>> {
        return callbackFlow {
            auth.currentUser?.let { user ->
                val postListener = object : ValueEventListener {
                    override fun onCancelled(error: DatabaseError) {
                        this@callbackFlow.trySendBlocking(emptyList())
                    }

                    override fun onDataChange(dataSnapshot: DataSnapshot) {
                        val items = dataSnapshot.children.map { item ->
                            item.getValue(DataEntity::class.java)
                        }.mapNotNull {
                            it?.let {
                                DataEntity(it.id, it.uid, it.content)
                            }
                        }
                        this@callbackFlow.trySendBlocking(items)
                    }
                }
                db.reference.child(DATA_PATH).child(user.uid).addValueEventListener(postListener)

                awaitClose {
                    db.reference.child(DATA_PATH).child(user.uid)
                        .addValueEventListener(postListener)
                }
            }
        }
    }

    suspend fun getData(id: String): Response<DataEntity, Error> {
        val uid = auth.currentUser?.uid
        if (uid != null) {
            val query = db.reference.child(DATA_PATH).child(uid).child(id)
            val dataSnapshot = query.get().await()
            try {
                val item = dataSnapshot.getValue(DataEntity::class.java) ?: return Response.Failure(Error.NotFound)
                return Response.Success(item)
            } catch (e: Exception) {
                return Response.Failure(Error.Connection(e.message.toString()))
            }
        } else {
            return Response.Failure(Error.Authentication)
        }
    }

    suspend fun getAll(): Response<List<DataEntity>, Error> {
        val uid = auth.currentUser?.uid
        return if (uid != null) {
            val query = db.reference.child(DATA_PATH).child(uid)
            val dataSnapshot = query.get().await()
            try {
                val items = dataSnapshot.children.mapNotNull { item ->
                    item.getValue(DataEntity::class.java)
                }
                return Response.Success(items)
            } catch (e: Exception) {
                Response.Failure(Error.Connection(e.message.toString()))
            }

        } else {
            Response.Failure(Error.Authentication)
        }

    }


    fun saveData(dataEntity: DataEntity, onSuccess: () -> Unit) {
        auth.currentUser?.let { user ->
            db.reference
                .child(DATA_PATH)
                .child(user.uid)
                .child(dataEntity.id)
                .setValue(dataEntity)
                .addOnCompleteListener {
                    onSuccess()
                }
        }
    }

    fun deleteData(id: String, onSuccess: () -> Unit) {
        auth.currentUser?.let { user ->
            db.reference
                .child(DATA_PATH)
                .child(user.uid)
                .child(id)
                .removeValue().addOnCompleteListener {
                    onSuccess()
                }
        }
    }


    fun recoverAccount() {
        TODO("Not yet implemented")
    }

    fun login(
        email: String,
        password: String,
        onResult: (Response<UserModel, Error>) -> Unit
    ) {
        try {
            if (email.isNotBlank() && password.isNotBlank()) {
                auth.signInWithEmailAndPassword(email, password).addOnSuccessListener {
                    if (it.user != null) {
                        val user = UserModel(it.user!!.uid, it.user!!.email.toString())
                        onResult(Response.Success(user))
                    } else {
                        onResult(Response.Failure(Error.Authentication))
                    }
                }.addOnFailureListener {
                    onResult(Response.Failure(Error.Authentication))
                }
            } else {
                onResult(Response.Failure(Error.InputError))
            }
        } catch (e: Exception) {
            onResult(Response.Failure(Error.Connection(e.message.toString())))
        }
    }

    fun register(
        email: String,
        password: String,
        onResult: (Response<UserModel, Error>) -> Unit
    ) {
        try {
            if (email.isNotBlank() && password.isNotBlank()) {
                auth.createUserWithEmailAndPassword(email, password)
                    .addOnSuccessListener lambda@{ result ->
                        if (result.user != null) {
                            val user = UserModel(result.user!!.uid, result.user!!.email.toString())
                            db.getReference(USER_PATH).child(user.uid).setValue(user)
                                .addOnCompleteListener {
                                    onResult(Response.Success(user))
                                }.addOnFailureListener {
                                    onResult(Response.Failure(Error.Authentication))
                                }
                        } else {
                            onResult(Response.Failure(Error.Authentication))
                        }
                    }.addOnFailureListener {
                        onResult(Response.Failure(Error.Authentication))
                    }
            } else {
                onResult(Response.Failure(Error.Authentication))
            }
        } catch (e: Exception) {
            onResult(Response.Failure(Error.Connection(e.message.toString())))
        }
    }

    fun logout() {
        auth.signOut()
    }

    fun getUser(): UserModel? {
        return auth.currentUser?.let {
            UserModel(it.uid, it.email.toString())
        }
    }

}